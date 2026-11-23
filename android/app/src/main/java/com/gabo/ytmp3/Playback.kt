package com.gabo.ytmp3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reproductor en segundo plano (Media3). Usa DOS reproductores ("platos"): para el crossfade
 * y para las mezclas del Modo DJ. Al terminar cada mezcla, la sesión (notificación, pantalla
 * de bloqueo, audífonos) pasa al plato que quedó sonando.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "DSPlay"
        @Volatile var instance: PlaybackService? = null
            private set
        /** ¿Suena música ahora? (el analizador se calma mientras tanto). */
        @Volatile var playingNow = false
            private set
        private val listeners = mutableSetOf<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())
        fun addListener(l: () -> Unit) { listeners += l }
        fun removeListener(l: () -> Unit) { listeners -= l }
        fun notifyUi() = main.post { listeners.toList().forEach { it() } }

        val CMD_SHUFFLE = SessionCommand("ds.shuffle", Bundle.EMPTY)
        val CMD_REPEAT = SessionCommand("ds.repeat", Bundle.EMPTY)
    }

    private lateinit var p1: ExoPlayer
    private lateinit var p2: ExoPlayer
    lateinit var active: ExoPlayer
        private set
    private val other get() = if (active === p1) p2 else p1
    private lateinit var session: MediaSession
    private val enhancers = HashMap<ExoPlayer, LoudnessEnhancer?>()
    private val eqs = HashMap<ExoPlayer, Equalizer?>()
    private val procs = HashMap<ExoPlayer, DjProcessor>()

    // Modo DJ
    var dj: DjSession? = null
        private set
    var djMood: Mood? = null
        private set
    private var djSegStart = 0L
    private var djSegEnd = 0L
    private var djInternal = false
    /** Velocidad del plato que suena. */
    private var curSpeed = 1f
    /** La próxima mezcla ya decidida (y si el otro plato ya está listo con ella). */
    private var nextStep: DjStep? = null
    private var armed = false
    /** Mezcla en curso. */
    private var mixing: DjStep? = null
    private var lastAdj = 0f
    val djUpcoming: List<Track> get() = listOfNotNull(nextStep?.track) + (dj?.upcoming ?: emptyList())
    val djSegment: Pair<Long, Long> get() = djSegStart to djSegEnd
    val djMixLabel: String? get() = if (manualT != null) "Mezcla manual (crossfader)" else mixing?.mix?.label
    /** Avance de la mezcla en curso 0..1 (el crossfader de la pantalla lo sigue). */
    val mixProgress: Float get() = if (!fading || mixing == null) 0f
        else manualT ?: ((SystemClock.elapsedRealtime() - fadeStart).toFloat() / fadeLen).coerceIn(0f, 1f)
    /** Posición del crossfader cuando la mezcla la hace el usuario (null = automática). */
    private var manualT: Float? = null
    private var scratching = false
    private var scratchSeekAt = 0L
    private var scratchParamsAt = 0L
    private var scratchBack = 0f
    private lateinit var prefs: Prefs2

    /** Cola actual (en orden de la playlist). */
    var queue: List<Track> = emptyList()
        private set
    var playlistId: Long? = null
        private set
    var playlistName: String = ""
        private set
    var showVideo = false
        private set

    private var fading = false
    private var fadeStart = 0L
    private var fadeLen = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private var analyzerJob: Job? = null

    private val levelSink = object : TeeAudioProcessor.AudioBufferSink {
        private var enc = C.ENCODING_PCM_16BIT
        private var lp = 0f
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) { enc = encoding }
        override fun handleBuffer(buffer: ByteBuffer) {
            if (enc != C.ENCODING_PCM_16BIT) return
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0; var sumLow = 0.0; var n = 0
            while (b.remaining() >= 2) {
                val s = b.short / 32768f
                lp += (s - lp) * 0.02f
                sum += s * s; sumLow += lp * lp; n++
                AudioData.push(s)
                if (b.remaining() >= 14) b.position(b.position() + 6)   // muestreo cada 4
            }
            if (n == 0) return
            val rms = sqrt(sum / n).toFloat(); val low = sqrt(sumLow / n).toFloat()
            AudioLevels.level = (rms * 3.2f).coerceIn(0f, 1f)
            AudioLevels.bass = (low * 6f).coerceIn(0f, 1f)
        }
    }

    private fun build(): ExoPlayer {
        val dp = DjProcessor()
        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                        enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(dp, TeeAudioProcessor(levelSink)))
                    .build()
        }
        // El foco de audio lo maneja el servicio (un solo dueño para los dos platos).
        // MP3 de bitrate variable sin cabecera: con "index seeking" los tiempos son exactos (antes se estimaba la
        // duración con el primer cuadro: salía 20 min en una canción de 4 y los cortes caían en cualquier parte).
        val ef = androidx.media3.extractor.DefaultExtractorsFactory()
            .setMp3ExtractorFlags(androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        val p = ExoPlayer.Builder(this, rf, androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, ef))
            .setAudioAttributes(attrs, false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        procs[p] = dp
        val sid = Util.generateAudioSessionIdV21(this)
        p.setAudioSessionId(sid)
        enhancers[p] = runCatching { LoudnessEnhancer(sid).apply { enabled = true } }.getOrNull()
        eqs[p] = runCatching { Equalizer(0, sid) }.getOrNull()?.also { Eq.apply(it, Prefs2(this)) }
        p.addListener(PlayerListener(p))
        return p
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs2(this)
        BlackBox.log(this, "servicio iniciado")
        p1 = build(); p2 = build()
        active = p1
        active.shuffleModeEnabled = prefs.shuffle
        active.repeatMode = prefs.repeat
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Canal propio con importancia normal (sin sonido): así también se ve en la pantalla de bloqueo.
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(App.CHANNEL_PLAYBACK).setChannelName(R.string.channel_playback).build())
        session = MediaSession.Builder(this, active)
            .setSessionActivity(open)
            .setCallback(SessionCallback())
            .build()
        updateButtons()
        instance = this
        handler.post(tick)
        notifyUi()
        restore()
        androidx.core.content.ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(this, power, IntentFilter(Intent.ACTION_POWER_CONNECTED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // Canciones recién analizadas: el DJ las suma a la mezcla en vivo
        analyzerJob = App.scope.launch { Analyzer.analyzed.collect { t -> handler.post { onAnalyzed(t) } } }
    }

    /** Si Android cierra el proceso, no volver a abrir el servicio solo (eso dejaba la notificación pegada). */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun onAnalyzed(t: Track) {
        val i = queue.indexOfFirst { it.id == t.id }
        if (i >= 0) queue = queue.toMutableList().also { it[i] = t }
        dj?.update(t)
        if (nextStep?.track?.id == t.id && !armed) planNext(false)
    }

    /** Pantalla de bloqueo propia: al apagar la pantalla con música sonando. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!prefs.lockScreen || !active.playWhenReady || active.mediaItemCount == 0) return
            if (!Settings.canDrawOverlays(c)) return
            runCatching {
                startActivity(Intent(c, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            }
        }
    }

    /** Con el cargador puesto se aprovecha para analizar lo que falte. */
    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { Analyzer.kick(c) }
    }

    fun applyEq() { val pr = Prefs2(this); eqs.values.forEach { e -> e?.let { Eq.apply(it, pr) } } }

    /** Aleatorio que prioriza lo que menos ha sonado y no repite hasta pasar por todas. */
    private fun smartOrder(list: List<Track>, first: Int): ShuffleOrder {
        val rnd = java.util.Random()
        val idx = list.indices.sortedBy { list[it].playCount + rnd.nextFloat() * 3f }.toMutableList()
        if (first in idx) { idx.remove(first); idx.add(0, first) }
        return ShuffleOrder.DefaultShuffleOrder(idx.toIntArray(), rnd.nextLong())
    }

    // ------------------------------------------------------------------ foco de audio (un solo dueño)
    private val am by lazy { getSystemService(AudioManager::class.java) }
    private var hasFocus = false
    private var resumeOnGain = false
    private var duck = 1f
    private val focusReq by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ c -> handler.post { onFocus(c) } }, handler)
            .build()
    }

    private fun ensureFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) BlackBox.log(this, "Android no dio el audio (otra app lo tiene)")
        return hasFocus
    }

    private fun onFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                BlackBox.log(this, "otra app tomó el audio (pausa)")
                hasFocus = false; resumeOnGain = false
                am.abandonAudioFocusRequest(focusReq)
                pauseAll()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                BlackBox.log(this, "pausa breve por otra app (llamada o aviso)")
                if (active.playWhenReady) { resumeOnGain = true; pauseAll() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duck = 0.25f; refreshVolumes() }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                duck = 1f; refreshVolumes()
                if (resumeOnGain) { resumeOnGain = false; active.play() }
            }
        }
    }

    private fun pauseAll() {
        if (fading) finishMixNow()
        active.pause()
    }

    private fun refreshVolumes() { if (!fading) active.volume = volFor(trackOf(active)) }

    // ------------------------------------------------------------------ recordar lo último que sonaba
    private fun save() {
        if (queue.isEmpty()) return
        prefs.qIds = queue.map { it.id }
        prefs.qIndex = active.currentMediaItemIndex
        prefs.qPos = active.currentPosition
        prefs.qPlaylist = playlistId ?: Long.MIN_VALUE
        prefs.qName = playlistName
        prefs.qVideo = showVideo
    }

    /** Deja lista la última canción, sin prepararla: así no aparece ninguna notificación sola. */
    private fun restore() {
        val ids = prefs.qIds
        if (ids.isEmpty() || queue.isNotEmpty()) return
        App.scope.launch {
            val map = Library.dao(this@PlaybackService).tracksByIds(ids).associateBy { it.id }
            val list = ids.mapNotNull { map[it] }
            if (list.isEmpty()) return@launch
            handler.post {
                if (queue.isNotEmpty() || instance == null) return@post
                queue = list
                playlistId = prefs.qPlaylist.takeIf { it != Long.MIN_VALUE }
                playlistName = prefs.qName; showVideo = prefs.qVideo
                val idx = prefs.qIndex.coerceIn(list.indices)
                val items = list.map(::item)
                for (p in listOf(p1, p2)) {
                    p.setMediaItems(items, idx, if (p === active) prefs.qPos else 0)
                    p.setShuffleOrder(smartOrder(list, idx))
                }
                active.volume = volFor(list[idx]); applyBoost(active, list[idx])
                active.playWhenReady = false
                notifyUi()
            }
        }
    }

    private fun bump(t: Track?) {
        t ?: return
        val now = System.currentTimeMillis()
        App.scope.launch { runCatching { Library.dao(this@PlaybackService).bump(t.id, now); Library.dao(this@PlaybackService).insertPlay(Play(trackId = t.id, at = now)) } }
    }

    private var saveTicks = 0
    /** Errores seguidos de reproducción (para no saltar canciones sin fin). */
    private var errStreak = 0

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /** Al cerrar la app desde "recientes": se cierra de verdad (sin música ni notificación). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        BlackBox.log(this, "app cerrada desde recientes")
        save()
        exitDj()
        cancelFade()
        for (p in listOf(p1, p2)) { p.stop(); p.clearMediaItems() }
        queue = emptyList()
        if (hasFocus) { am.abandonAudioFocusRequest(focusReq); hasFocus = false }
        PlayerHub.release()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { NotificationManagerCompat.from(this).cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID) }
        if (!DownloadState.running) runCatching { stopService(Intent(this, DownloadService::class.java)) }
        stopSelf()
        // (otro Handler: el del servicio se vacía en onDestroy y cancelaba este cierre)
        if (!DownloadState.running) main.postDelayed({ if (!DownloadState.running) android.os.Process.killProcess(android.os.Process.myPid()) }, 700)
    }

    override fun onDestroy() {
        save()
        runCatching { unregisterReceiver(screenOff) }
        runCatching { unregisterReceiver(power) }
        analyzerJob?.cancel()
        instance = null
        playingNow = false
        handler.removeCallbacksAndMessages(null)
        if (hasFocus) runCatching { am.abandonAudioFocusRequest(focusReq) }
        eqs.values.forEach { it?.release() }
        session.release()
        enhancers.values.forEach { it?.release() }
        p1.release(); p2.release()
        super.onDestroy()
        notifyUi()
    }

    // ------------------------------------------------------------------ cola
    fun current(): Track? = active.currentMediaItem?.mediaId?.toLongOrNull()?.let { id -> queue.firstOrNull { it.id == id } }

    private fun item(t: Track): MediaItem {
        val art = Covers.fileFor(this, t)
        val uri = if (showVideo && t.hasVideo) t.videoUri()!! else t.audioUri()
        val clip = MediaItem.ClippingConfiguration.Builder().apply {
            if (prefs.skipSilence && dj == null && t.anaVer >= Analyzer.VER) {
                val start = t.introMs?.takeIf { it in 301..20_000 } ?: 0L
                val end = t.outroMs?.takeIf { it > start + 30_000 && (t.durationMs == 0L || it < t.durationMs - 500) }
                if (start > 0 && (t.durationMs == 0L || start < t.durationMs - 30_000)) setStartPositionMs(start)
                end?.let { setEndPositionMs(it) }
            }
        }.build()
        return MediaItem.Builder().setMediaId(t.id.toString()).setUri(uri).setClippingConfiguration(clip)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(t.title).setArtist(t.artist.ifEmpty { null })
                .setAlbumTitle(playlistName.ifEmpty { null })
                .setArtworkUri(Uri.fromFile(art))
                .build())
            .build()
    }

    /** Reproduce [tracks] desde [start]. [shuffleStart] = botón aleatorio de la playlist. */
    fun play(tracks: List<Track>, start: Int, plId: Long?, plName: String, video: Boolean, shuffleStart: Boolean = false) {
        if (tracks.isEmpty()) return
        cancelFade()
        exitDj()
        queue = tracks; playlistId = plId; playlistName = plName; showVideo = video
        if (shuffleStart) setShuffle(true)
        val items = tracks.map(::item)
        val idx = if (shuffleStart) (tracks.indices).random() else start.coerceIn(tracks.indices)
        for (p in listOf(p1, p2)) {
            p.stop()
            p.setMediaItems(items, idx, 0)
            p.setShuffleOrder(smartOrder(tracks, idx))
            p.setPlaybackSpeed(1f)
            procs[p]?.resetFx()
        }
        active.volume = volFor(tracks[idx])
        applyBoost(active, tracks[idx])
        active.prepare()
        active.play()
        bump(tracks[idx]); save()
        // Mientras escuchas, se va preparando el DJ de esta playlist
        Analyzer.prioritize(this, tracks.map { it.id }, now = false)
        notifyUi()
    }

    /** Cambia "Mostrar video" sin cortar: mismo punto de la misma canción. */
    fun setShowVideo(v: Boolean) {
        if (v == showVideo || queue.isEmpty()) { showVideo = v; return }
        cancelFade()
        showVideo = v
        val idx = active.currentMediaItemIndex
        val pos = active.currentPosition
        val playing = active.playWhenReady
        val items = queue.map(::item)
        for (p in listOf(p1, p2)) p.setMediaItems(items, idx, if (p === active) pos else 0)
        active.prepare()
        active.playWhenReady = playing
        notifyUi()
    }

    /** Refresca títulos/portadas tras editar una canción. */
    fun refreshTrack(t: Track) {
        val i = queue.indexOfFirst { it.id == t.id }
        if (i < 0) return
        queue = queue.toMutableList().also { it[i] = t }
        for (p in listOf(p1, p2)) if (i < p.mediaItemCount) p.replaceMediaItem(i, item(t))
        notifyUi()
    }

    fun setShuffle(on: Boolean) {
        prefs.shuffle = on
        if (on && queue.isNotEmpty() && dj == null) {
            val order = smartOrder(queue, active.currentMediaItemIndex)
            for (p in listOf(p1, p2)) if (p.mediaItemCount == queue.size) p.setShuffleOrder(order)
        }
        if (dj != null) { updateButtons(); notifyUi(); return }
        p1.shuffleModeEnabled = on; p2.shuffleModeEnabled = on
        updateButtons(); notifyUi()
    }

    fun cycleRepeat() {
        val next = when (active.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        prefs.repeat = next
        p1.repeatMode = next; p2.repeatMode = next
        updateButtons(); notifyUi()
    }

    fun togglePlay() {
        if (active.isPlaying) { active.pause(); return }
        if (active.playbackState == Player.STATE_IDLE) active.prepare()
        if (active.playbackState == Player.STATE_ENDED) active.seekTo(0, 0)
        active.play()
    }
    fun next() { if (dj != null) { djNext(true); return }; cancelFade(); if (active.hasNextMediaItem()) active.seekToNextMediaItem() }
    fun prev() { cancelFade(); active.seekToPrevious() }
    fun seekTo(ms: Long) { if (dj != null && fading) finishMixNow(); cancelFade(); active.seekTo(ms); if (dj != null) armed = false }
    fun jumpTo(index: Int) { cancelFade(); active.seekTo(index, 0); active.play() }

    // ------------------------------------------------------------------ volumen nivelado
    private fun volFor(t: Track?): Float {
        val g = t?.gainDb ?: 0f
        val v = if (!prefs.normalize || g >= 0f) 1f else 10f.pow(g / 20f)
        return v * duck
    }

    private fun applyBoost(p: ExoPlayer, t: Track?) {
        val g = t?.gainDb ?: 0f
        val mb = if (prefs.normalize && g > 0f) (g * 100).toInt() else 0
        runCatching { enhancers[p]?.setTargetGain(mb) }
    }

    /**
     * Duración real de lo que suena: la del archivo si se conoce; si no (MP3 sin cabecera), la medida al analizar.
     */
    val durMs: Long get() = durOf(active)

    private fun durOf(p: ExoPlayer): Long {
        val d = p.duration
        if (d != C.TIME_UNSET && d > 0) return d
        return trackOf(p)?.durationMs?.takeIf { it > 0 } ?: C.TIME_UNSET
    }

    private fun trackOf(p: ExoPlayer): Track? =
        p.currentMediaItem?.mediaId?.toLongOrNull()?.let { id -> queue.firstOrNull { it.id == id } }

    // ------------------------------------------------------------------ reloj
    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (e: Exception) { Log.w(TAG, "tick", e); BlackBox.log(this@PlaybackService, "error interno: ${e.javaClass.simpleName}") }
            if (++saveTicks >= 40) { saveTicks = 0; if (active.isPlaying) save() }
            val near = dj != null && !fading && nextStep != null && active.isPlaying &&
                (djT0() - active.currentPosition) / curSpeed < 4000
            handler.postDelayed(this, if (fading || near) 40 else 250)
        }
    }

    private fun step() {
        if (dj != null) { djStep(); return }
        if (!fading) {
            if (!prefs.crossfadeOn || !active.isPlaying || active.repeatMode == Player.REPEAT_MODE_ONE) return
            if (!active.hasNextMediaItem()) return
            val d = durOf(active)
            if (d == C.TIME_UNSET) return
            val cf = prefs.crossfadeSec * 1000L
            if (d < cf * 2 + 4000) return
            val left = d - active.currentPosition
            if (left in 1..cf) startFade(left)
            return
        }
        val t = ((SystemClock.elapsedRealtime() - fadeStart).toFloat() / fadeLen).coerceIn(0f, 1f)
        val out = active; val inc = other
        out.volume = volFor(trackOf(out)) * cos(t * PI / 2).toFloat()
        inc.volume = volFor(trackOf(inc)) * sin(t * PI / 2).toFloat()
        if (t >= 1f) completeFade()
    }

    private fun startFade(left: Long) {
        val idx = active.nextMediaItemIndex
        if (idx == C.INDEX_UNSET) return
        val inc = other
        inc.shuffleModeEnabled = active.shuffleModeEnabled
        inc.repeatMode = active.repeatMode
        inc.seekTo(idx, 0)
        inc.setPlaybackSpeed(1f)
        inc.volume = 0f
        procs[inc]?.resetFx()
        applyBoost(inc, queue.firstOrNull { it.id.toString() == inc.getMediaItemAt(idx).mediaId })
        inc.prepare()
        inc.play()
        fading = true
        fadeStart = SystemClock.elapsedRealtime()
        fadeLen = (left - 250).coerceAtLeast(500)
    }

    private fun completeFade() {
        if (!fading) return
        fading = false
        manualT = null
        val out = active
        val inc = other
        out.pause()
        out.setPlaybackParameters(PlaybackParameters(1f))
        procs[out]?.resetFx()
        active = inc
        inc.volume = volFor(trackOf(inc))
        session.player = inc
        playingNow = inc.playWhenReady
        val m = mixing
        mixing = null
        if (dj != null && m != null) {
            procs[inc]?.resetFx()
            inc.setPlaybackSpeed(m.speed)
            curSpeed = m.speed
            djSegStart = m.part.first; djSegEnd = m.part.second
            planNext(false)
        }
        updateButtons()
        bump(trackOf(inc)); save()
        notifyUi()
    }

    private fun cancelFade() {
        if (!fading) return
        fading = false
        mixing = null
        other.pause()
        procs[other]?.resetFx()
        active.volume = volFor(trackOf(active))
        procs[active]?.resetFx()
    }

    // ------------------------------------------------------------------ Modo DJ
    /** Empieza el DJ: toda la playlist, partes distintas de cada canción y mezclas variadas. */
    fun startDj(tracks: List<Track>, mood: Mood?, start: Track?, name: String, scores: Map<Long, Float>, plId: Long = -4) {
        if (tracks.isEmpty()) return
        cancelFade()
        if (fading) completeFade()
        nextStep = null; armed = false; mixing = null
        val s = DjSession(this, tracks, mood, scores)
        val first = s.first(start)
        dj = s; djMood = mood
        queue = tracks; playlistId = plId; playlistName = name; showVideo = false
        val items = tracks.map(::item)
        val idx = tracks.indexOfFirst { it.id == first.id }.coerceAtLeast(0)
        for (p in listOf(p1, p2)) {
            p.stop(); p.setMediaItems(items, idx, 0)
            p.shuffleModeEnabled = false; p.repeatMode = Player.REPEAT_MODE_OFF; p.setPlaybackSpeed(1f)
            procs[p]?.resetFx()
        }
        val (st, en) = s.segment(first)
        djSegStart = st; djSegEnd = en; curSpeed = 1f
        djInternal = true
        active.seekTo(idx, st)
        djInternal = false
        active.volume = volFor(first); applyBoost(active, first)
        active.prepare(); active.play()
        planNext(false)
        BlackBox.log(this, "DJ iniciado con ${tracks.size} canciones")
        bump(first); updateButtons(); notifyUi()
    }

    /** Decide la siguiente mezcla (canción, parte, tipo y duración). */
    private fun planNext(skip: Boolean) {
        val s = dj ?: return
        val (nt, speed) = nextStep?.takeIf { !skip || armed }?.let { it.track to it.speed } ?: s.advance()
        val fresh = s.track(nt.id) ?: nt
        val seg = nextStep?.takeIf { it.track.id == fresh.id && armed }?.part ?: s.segment(fresh)
        val (mix, len, fx) = s.mixFor(trackOf(active), fresh, curSpeed, seg, skip, prefs.djFxVolume)
        val entry = when {
            mix.overlap -> (seg.first - (len * speed).toLong()).coerceAtLeast(0)
            mix == Mix.SPIN -> (seg.first - (len * 0.85f * speed).toLong()).coerceAtLeast(0)
            else -> seg.first
        }
        val prevArmed = armed && nextStep?.track?.id == fresh.id && nextStep?.entry == entry
        nextStep = DjStep(fresh, speed, seg, entry, mix, len, fx)
        armed = prevArmed
        notifyUi()
    }

    /** Punto (en la canción que suena) donde empieza la mezcla para terminar justo al final de la parte. */
    private fun djT0(): Long {
        val n = nextStep ?: return djSegEnd
        val span = when (n.mix) { Mix.CUT -> 0L; else -> (n.lenMs * curSpeed).toLong() }
        return djSegEnd - span
    }

    private fun djStep() {
        if (fading) { djMixStep(); return }
        if (scratching) return
        val n = nextStep ?: return
        if (!active.isPlaying) return
        // Nunca pasarse del final real del archivo
        val d = active.duration
        if (d != C.TIME_UNSET && d > 0 && djSegEnd > d - 400) djSegEnd = d - 400
        val pos = active.currentPosition
        val t0 = djT0()
        // Preparar el otro plato unos segundos antes (así entra exacto, sin demora)
        if (!armed && pos >= t0 - (4500 * curSpeed).toLong()) arm(n)
        val leftReal = ((t0 - pos) / curSpeed).toLong()
        if (leftReal <= 45) beginMix()
        else if (leftReal < 90) handler.postDelayed({ if (!fading && dj != null && nextStep === n) beginMix() }, leftReal)
    }

    private fun arm(n: DjStep) {
        val idx = queue.indexOfFirst { it.id == n.track.id }
        if (idx < 0) { armed = false; planNext(true); return }
        val inc = other
        djInternal = true
        inc.pause()
        inc.seekTo(idx, n.entry)
        djInternal = false
        inc.setPlaybackParameters(PlaybackParameters(n.speed))
        inc.volume = 0f
        procs[inc]?.resetFx()
        procs[inc]?.apply {
            when (n.mix) {
                Mix.EQ -> { low = 0f; mid = 0.35f; high = 0.7f }
                Mix.BASS, Mix.LOOP -> { low = 0f; mid = 0.5f }
                Mix.FILTER -> { lpHz = 400f; mid = 0.6f }
                else -> {}
            }
        }
        applyBoost(inc, n.track)
        inc.playWhenReady = false
        inc.prepare()
        armed = true
    }

    private fun beginMix() {
        val n = nextStep ?: return
        if (fading) return
        if (!armed) arm(n)
        val out = active; val inc = other
        mixing = n
        nextStep = null; armed = false
        fading = true
        fadeStart = SystemClock.elapsedRealtime()
        fadeLen = n.lenMs.coerceAtLeast(1)
        lastAdj = 0f
        AudioLevels.djEvent++
        BlackBox.log(this, "mezcla ${n.mix.name}: «${trackOf(out)?.title?.take(30)}» → «${n.track.title.take(30)}»")
        inc.play()
        if (n.mix == Mix.CUT) {
            inc.volume = volFor(n.track)
            out.volume = 0f
            completeFade()
            return
        }
        val outBeat = 60000f / (trackOf(out)?.bpm ?: 120f)   // ms de la canción (la consola trabaja antes del cambio de velocidad)
        if (n.mix == Mix.ECHO) procs[out]?.apply { delayMs = outBeat * 0.75f; feedback = 0.35f + 0.2f * prefs.djFxVolume; send = 0.55f }
        if (n.mix == Mix.LOOP) procs[out]?.loopMs = outBeat * 4
        djMixStep()
        notifyUi()
    }

    /** Curvas de cada tipo de mezcla, y el "pulso" que mantiene los golpes alineados. */
    private fun djMixStep() {
        val m = mixing ?: run { step0(); return }
        val out = active; val inc = other
        val po = procs[out]; val pi = procs[inc]
        val t = manualT ?: ((SystemClock.elapsedRealtime() - fadeStart).toFloat() / fadeLen).coerceIn(0f, 1f)
        val vo = volFor(trackOf(out)); val vi = volFor(m.track)
        fun ease(x: Float) = sin(x.coerceIn(0f, 1f) * PI.toFloat() / 2)
        val outBeat = 60000f / (trackOf(out)?.bpm ?: 120f)
        fun ramp(x: Float, a: Float, b: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f)
        fun lin(a: Float, b: Float, u: Float) = a + (b - a) * u
        when (m.mix) {
            // Ecualizador de 3 bandas: entran los agudos, luego los medios; los bajos se cambian de golpe a mitad de la frase
            Mix.EQ -> {
                inc.volume = vi * ease(t / 0.25f)
                pi?.high = lin(0.7f, 1f, ramp(t, 0f, 0.25f))
                pi?.mid = lin(0.35f, 0.7f, ramp(t, 0.1f, 0.5f)).let { if (t > 0.5f) lin(0.7f, 1f, ramp(t, 0.5f, 0.8f)) else it }
                po?.high = lin(1f, 0.55f, ramp(t, 0.15f, 0.5f))
                val u = ramp(t, 0.5f, 0.5f + 0.0625f); po?.low = 1f - u; pi?.low = u   // un tiempo de cada 16
                po?.mid = lin(1f, 0.3f, ramp(t, 0.5f, 0.85f))
                out.volume = vo * (if (t < 0.7f) 1f else cos(ramp(t, 0.7f, 1f) * PI.toFloat() / 2))
            }
            // Cambio de bajos corto (2 compases)
            Mix.BASS -> {
                inc.volume = vi * ease(t / 0.25f)
                pi?.mid = lin(0.5f, 1f, ramp(t, 0.5f, 0.75f))
                val u = ramp(t, 0.5f, 0.5f + 0.125f); po?.low = 1f - u; pi?.low = u
                po?.mid = lin(1f, 0.4f, ramp(t, 0.5f, 0.8f))
                out.volume = vo * (if (t < 0.75f) 1f else cos(ramp(t, 0.75f, 1f) * PI.toFloat() / 2))
            }
            // La que sale se adelgaza (pasa-altos) y la que entra se abre (pasa-bajos)
            Mix.FILTER -> {
                po?.hpHz = if (t < 0.02f) 0f else 30f * (900f / 30f).pow(t)
                pi?.lpHz = 400f * (20000f / 400f).pow(ramp(t, 0f, 0.9f))
                pi?.mid = lin(0.6f, 1f, ramp(t, 0.5f, 0.9f))
                inc.volume = vi * ease(t / 0.3f)
                out.volume = vo * (if (t < 0.7f) 1f else cos(ramp(t, 0.7f, 1f) * PI.toFloat() / 2))
            }
            // Eco a tempo: el último golpe se repite y se apaga mientras entra la siguiente en su tiempo fuerte
            Mix.ECHO -> {
                po?.send = if (t < 0.25f) 0.55f else 0f
                po?.dry = 1f - ramp(t, 0f, 0.125f)
                po?.hpHz = 120f * ramp(t, 0f, 0.5f)
                out.volume = vo
                inc.volume = vi * ease(t / 0.15f)
            }
            // Loop que crece: 1 compás → 2 tiempos → 1 → ½, la que sale se adelgaza y cae el drop de la que entra
            Mix.LOOP -> {
                po?.loopMs = outBeat * when { t < 0.5f -> 4f; t < 0.75f -> 2f; t < 0.875f -> 1f; else -> 0.5f }
                po?.hpHz = if (t < 0.3f) 0f else 40f * (1200f / 40f).pow(ramp(t, 0.3f, 1f))
                po?.low = 1f - ramp(t, 0.85f, 1f)
                out.volume = vo * (1f - 0.35f * ramp(t, 0.6f, 1f))
                inc.volume = vi * lin(0f, 0.75f, ramp(t, 0f, 0.5f)).let { if (t > 0.9f) lin(0.75f, 1f, ramp(t, 0.9f, 1f)) else it }
                pi?.low = ramp(t, 0.95f, 1f)
                pi?.mid = lin(0.5f, 1f, ramp(t, 0.75f, 1f))
            }
            Mix.SPIN -> {
                val sp = (1f - 0.8f * t.pow(1.5f)).coerceAtLeast(0.2f)
                out.setPlaybackParameters(PlaybackParameters(curSpeed * sp, sp))
                out.volume = vo * (1f - t * t * t)
                inc.volume = if (t < 0.85f) 0f else vi
            }
            Mix.CUT -> {}
        }
        // Pulso: el plato que entra acelera o frena apenas para que su golpe caiga con el otro
        if (m.mix.overlap && t < 0.92f && inc.isPlaying && out.isPlaying) {
            val remainReal = (djSegEnd - out.currentPosition) / curSpeed
            val desired = m.part.first - remainReal * m.speed
            val err = desired - inc.currentPosition
            var adj = (err / 1500f).coerceIn(-0.03f, 0.03f)
            if (abs(err) < 25f) adj = 0f
            if (abs(adj - lastAdj) > 0.004f) { lastAdj = adj; inc.setPlaybackParameters(PlaybackParameters(m.speed * (1f + adj))) }
        }
        if (t >= 1f && manualT == null) completeFade()
    }

    private fun step0() { if (fading) completeFade() }

    /** Termina la mezcla en curso de golpe (pausa, cambio de canción manual…). */
    private fun finishMixNow() {
        if (!fading) return
        if (dj != null && mixing != null) completeFade() else cancelFade()
    }

    /** Siguiente del DJ. [skip] = botón "Saltar" (o notificación/audífonos): mezcla rápida. */
    fun djNext(skip: Boolean) {
        val s = dj ?: return
        if (fading) completeFade()
        val cur = trackOf(active)
        if (cur != null) {
            val listened = active.currentPosition - djSegStart
            val delta = if (skip && listened < 20_000) -1f else if (!skip) 0.4f else 0f
            if (delta != 0f) App.scope.launch { runCatching { DjBrain.learn(this@PlaybackService, cur.id, djMood, delta) } }
        }
        if (nextStep == null) planNext(skip)
        else if (skip) {
            val n = nextStep!!
            val (mix, len, fx) = s.mixFor(cur, n.track, curSpeed, n.part, true, prefs.djFxVolume)
            val entry = when {
                mix.overlap -> (n.part.first - (len * n.speed).toLong()).coerceAtLeast(0)
                mix == Mix.SPIN -> (n.part.first - (len * 0.85f * n.speed).toLong()).coerceAtLeast(0)
                else -> n.part.first
            }
            if (armed && entry != n.entry) armed = false
            nextStep = DjStep(n.track, n.speed, n.part, entry, mix, len, fx)
        }
        // la parte actual "termina" ahora
        djSegEnd = active.currentPosition + ((nextStep?.lenMs ?: 0L) * curSpeed).toLong()
        beginMix()
    }

    // ------------------------------------------------------------------ crossfader y scratch (pantalla del DJ)
    /** El usuario mueve el crossfader: 0 = canción actual, 1 = la que sigue. */
    fun crossfade(pos: Float) {
        dj ?: return
        if (!fading) {
            if (pos < 0.03f || scratching) return
            if (nextStep == null) planNext(false)
            val n = nextStep ?: return
            // mezcla con ecualizador de 3 bandas, con los golpes alineados
            val beat = 60000f / ((trackOf(active)?.bpm ?: 120f) * curSpeed)
            val len = (beat * 16).toLong().coerceIn(3000L, 16_000L)
            val entry = (n.part.first - (len * n.speed).toLong()).coerceAtLeast(0)
            if (armed && entry != n.entry) armed = false
            nextStep = DjStep(n.track, n.speed, n.part, entry, Mix.EQ, len, null)
            djSegEnd = active.currentPosition + (len * curSpeed).toLong()
            manualT = pos
            beginMix()
            return
        }
        if (mixing == null) return
        manualT = pos.coerceIn(0f, 1f)
        if (pos >= 0.985f) { manualT = null; completeFade(); return }
        djMixStep()
    }

    /** Suelta el crossfader: si volvió al inicio, se cancela la mezcla y sigue la canción actual. */
    fun crossfadeRelease(pos: Float) {
        if (!fading || manualT == null) return
        if (pos < 0.03f) {
            val m = mixing
            fading = false; manualT = null; mixing = null
            other.pause(); procs[other]?.resetFx()
            procs[active]?.resetFx(); active.volume = volFor(trackOf(active))
            if (m != null) { nextStep = m; armed = false }
            djSegEnd = active.currentPosition + 60_000
            notifyUi()
        }
    }

    fun scratchStart() {
        if (fading) { if (manualT != null) crossfadeRelease(0f) else finishMixNow() }
        scratching = true; scratchBack = 0f
    }

    /** [vel] grados/s (positivo = horario), [deg] grados desde el último aviso. 200 °/s = velocidad normal. */
    fun scratch(vel: Float, deg: Float) {
        if (!scratching) return
        val p = active
        val now = SystemClock.elapsedRealtime()
        val vol = volFor(trackOf(p))
        if (vel >= -15f) {
            // hacia adelante: más rápido cuanto más rápido gira (quieto = "frena" el disco)
            if (now - scratchParamsAt > 70) {
                scratchParamsAt = now
                val sp = (vel / 200f).coerceIn(0.35f, 3f)
                p.setPlaybackParameters(PlaybackParameters(curSpeed * sp, 1f))
                p.volume = if (vel < 40f) vol * 0.35f else vol
            }
        } else {
            // hacia atrás: retrocede lo que se giró
            scratchBack += -deg / 200f * 1000f * curSpeed
            if (now - scratchSeekAt > 110 && scratchBack > 30f) {
                scratchSeekAt = now
                p.volume = vol * 0.5f
                p.seekTo((p.currentPosition - scratchBack.toLong()).coerceAtLeast(0))
                scratchBack = 0f
            }
        }
    }

    fun scratchEnd() {
        if (!scratching) return
        scratching = false
        active.setPlaybackParameters(PlaybackParameters(curSpeed))
        active.volume = volFor(trackOf(active))
        if (active.playWhenReady.not()) active.play()
        // si retrocedió antes de donde estaba la parte, la parte sigue hasta su final de siempre
        if (active.currentPosition < djSegStart) djSegStart = active.currentPosition
        armed = false
        notifyUi()
    }

    /** El plato se quedó sin música (fin del archivo o error): seguir sin silencio. */
    private fun djRescue(why: String) {
        BlackBox.log(this, "rescate del DJ: $why")
        if (fading) completeFade()
        if (nextStep == null) planNext(true)
        nextStep?.let { n -> nextStep = DjStep(n.track, n.speed, n.part, n.part.first, Mix.CUT, 0L, null); armed = false }
        djSegEnd = active.currentPosition
        beginMix()
    }

    fun exitDj() {
        if (dj == null) return
        if (fading) completeFade()
        dj = null; djMood = null; nextStep = null; armed = false; mixing = null; curSpeed = 1f
        for (p in listOf(p1, p2)) {
            p.setPlaybackParameters(PlaybackParameters(1f)); procs[p]?.resetFx()
            p.shuffleModeEnabled = prefs.shuffle; p.repeatMode = prefs.repeat
        }
        updateButtons(); notifyUi()
    }

    private inner class PlayerListener(val p: ExoPlayer) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (p === active && fading && dj == null) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { completeFade(); return }
                if (!djInternal) cancelFade()
            }
            if (p === active && dj != null && !djInternal && !fading) {
                when (reason) {
                    // "siguiente" desde la notificación o los audífonos = saltar con mezcla
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> { handler.post { djNext(true) }; return }
                    // el archivo se acabó antes de tiempo: mezclar ya, sin silencio
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> { handler.post { djRescue("fin del archivo") }; return }
                }
            }
            if (p === active) {
                val t = trackOf(p)
                if (!fading) { p.volume = volFor(t); applyBoost(p, t) }
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && dj == null) { bump(t); save() }
                notifyUi()
            }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (p === active && playWhenReady && !ensureFocus()) { p.pause(); return }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (p === active) {
                playingNow = isPlaying
                if (!isPlaying && fading && !p.playWhenReady) { if (dj != null) { completeFade(); active.pause() } else { other.pause(); cancelFade() } }
                if (!isPlaying) save()
                notifyUi()
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            val t = trackOf(p)
            BlackBox.log(this@PlaybackService, "error al reproducir «${t?.title?.take(40)}»: ${error.errorCodeName}")
            if (dj != null) {
                if (p === active) handler.post { djRescue("archivo con error") }
                else { armed = false; handler.post { planNext(true) } }
                return
            }
            if (p !== active) return
            if (++errStreak > 3) {
                BlackBox.log(this@PlaybackService, "varias canciones seguidas no se pudieron reproducir: se detiene")
                errStreak = 0; p.playWhenReady = false; notifyUi(); return
            }
            if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (p !== active) return
            if (playbackState == Player.STATE_ENDED && dj != null) { handler.post { djRescue("terminó la lista") }; return }
            if (playbackState == Player.STATE_READY) errStreak = 0
            // Fin de la playlist sin "repetir": vuelve a la primera canción y queda en pausa
            if (playbackState == Player.STATE_ENDED && p.repeatMode == Player.REPEAT_MODE_OFF && p.mediaItemCount > 0) {
                handler.post {
                    if (p.shuffleModeEnabled) {
                        val order = smartOrder(queue, -1)
                        for (q in listOf(p1, p2)) if (q.mediaItemCount == queue.size) q.setShuffleOrder(order)
                    }
                    p.playWhenReady = false
                    p.seekTo(p.currentTimeline.getFirstWindowIndex(p.shuffleModeEnabled).coerceAtLeast(0), 0)
                    notifyUi()
                }
            }
            notifyUi()
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (p === active && dj == null && other.shuffleModeEnabled != shuffleModeEnabled) {
                prefs.shuffle = shuffleModeEnabled; other.shuffleModeEnabled = shuffleModeEnabled; updateButtons(); notifyUi()
            }
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            if (p === active && dj == null && other.repeatMode != repeatMode) {
                prefs.repeat = repeatMode; other.repeatMode = repeatMode; updateButtons(); notifyUi()
            }
        }
    }

    // ------------------------------------------------------------------ botones extra (notificación y bloqueo)
    private fun updateButtons() {
        if (!::session.isInitialized) return
        if (!prefs.lockButtons) { session.setCustomLayout(ImmutableList.of()); return }
        val sh = active.shuffleModeEnabled
        val rp = active.repeatMode
        session.setCustomLayout(ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName(if (sh) "Aleatorio: sí" else "Aleatorio: no")
                .setIconResId(if (sh) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                .setSessionCommand(CMD_SHUFFLE).build(),
            CommandButton.Builder()
                .setDisplayName(when (rp) { Player.REPEAT_MODE_ONE -> "Repetir una"; Player.REPEAT_MODE_ALL -> "Repetir todo"; else -> "Repetir: no" })
                .setIconResId(when (rp) { Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one; Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_on; else -> R.drawable.ic_repeat })
                .setSessionCommand(CMD_REPEAT).build()))
    }

    fun refreshButtons() = updateButtons()

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(CMD_SHUFFLE).add(CMD_REPEAT).build())
                .build()

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                     customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SHUFFLE.customAction -> setShuffle(!active.shuffleModeEnabled)
                CMD_REPEAT.customAction -> cycleRepeat()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}

/** Nivelar volumen: ahora lo hace el analizador completo (en segundo plano). */
object Loudness {
    fun kick(ctx: Context) = Analyzer.kick(ctx)
}
