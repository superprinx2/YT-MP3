package com.gabo.ytmp3

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Equalizer
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reproductor en segundo plano (Media3). Usa DOS reproductores para el crossfade: cuando la
 * canción está por terminar, el segundo empieza la siguiente y se funden; al acabar, la sesión
 * (notificación, pantalla de bloqueo, audífonos) pasa al segundo.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "DSPlay"
        @Volatile var instance: PlaybackService? = null
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

    // Modo DJ
    var dj: DjSession? = null
        private set
    var djMood: Mood? = null
        private set
    private var djSegStart = 0L
    private var djSegEnd = 0L
    private var djPending: Pair<Long, Long>? = null
    private var djInternal = false
    val djUpcoming: List<Track> get() = dj?.plan?.map { it.first }.orEmpty()
    val djSegment: Pair<Long, Long> get() = djSegStart to djSegEnd
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
        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                        enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(levelSink)))
                    .build()
        }
        val p = ExoPlayer.Builder(this, rf)
            .setAudioAttributes(attrs, false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
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
        p1 = build(); p2 = build()
        active = p1
        active.setAudioAttributes(attrs, true)
        active.shuffleModeEnabled = prefs.shuffle
        active.repeatMode = prefs.repeat
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Canal propio con importancia normal (sin sonido): así también se ve en la pantalla de bloqueo.
        setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
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
    }

    /** Pantalla de bloqueo propia: al apagar la pantalla con música sonando. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Log.i(TAG, "pantalla apagada: lock=${prefs.lockScreen} sonando=${active.playWhenReady} overlay=${Settings.canDrawOverlays(c)}")
            if (!prefs.lockScreen || !active.playWhenReady || active.mediaItemCount == 0) return
            if (!Settings.canDrawOverlays(c)) return
            runCatching {
                Log.i(TAG, "abriendo pantalla de bloqueo propia")
                startActivity(Intent(c, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            }
        }
    }

    fun applyEq() { val pr = Prefs2(this); eqs.values.forEach { e -> e?.let { Eq.apply(it, pr) } } }

    /** Aleatorio que prioriza lo que menos ha sonado y no repite hasta pasar por todas. */
    private fun smartOrder(list: List<Track>, first: Int): ShuffleOrder {
        val rnd = java.util.Random()
        val idx = list.indices.sortedBy { list[it].playCount + rnd.nextFloat() * 3f }.toMutableList()
        if (first in idx) { idx.remove(first); idx.add(0, first) }
        return ShuffleOrder.DefaultShuffleOrder(idx.toIntArray(), rnd.nextLong())
    }

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
                val seed = SystemClock.elapsedRealtime()
                for (p in listOf(p1, p2)) {
                    p.setMediaItems(items, idx, if (p === active) prefs.qPos else 0)
                    p.setShuffleOrder(smartOrder(list, idx))
                }
                active.volume = volFor(list[idx]); applyBoost(active, list[idx])
                active.playWhenReady = false
                active.prepare()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /** Al cerrar la app desde "recientes": se cierra de verdad (sin música ni notificación). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        save()
        exitDj()
        cancelFade()
        for (p in listOf(p1, p2)) { p.stop(); p.clearMediaItems() }
        queue = emptyList()
        PlayerHub.release()
        stopSelf()
        if (!DownloadState.running) handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
    }

    override fun onDestroy() {
        save()
        runCatching { unregisterReceiver(screenOff) }
        instance = null
        handler.removeCallbacksAndMessages(null)
        eqs.values.forEach { it?.release() }
        DjFx.release()
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
            if (prefs.skipSilence && dj == null) {
                t.introMs?.takeIf { it > 300 }?.let { setStartPositionMs(it) }
                t.outroMs?.takeIf { it > 0 && (t.durationMs == 0L || it < t.durationMs - 500) }?.let { setEndPositionMs(it) }
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
        val seed = SystemClock.elapsedRealtime()
        val idx = if (shuffleStart) (tracks.indices).random() else start.coerceIn(tracks.indices)
        other.setAudioAttributes(attrs, false)
        active.setAudioAttributes(attrs, true)
        for (p in listOf(p1, p2)) {
            p.stop()
            p.setMediaItems(items, idx, 0)
            p.setShuffleOrder(smartOrder(tracks, idx))
            p.setPlaybackSpeed(1f)
        }
        active.volume = volFor(tracks[idx])
        applyBoost(active, tracks[idx])
        active.prepare()
        active.play()
        bump(tracks[idx]); save()
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

    fun togglePlay() { if (active.isPlaying) active.pause() else { if (active.playbackState == Player.STATE_ENDED) active.seekTo(0, 0); active.play() } }
    fun next() { if (dj != null) { djNext(true); return }; cancelFade(); if (active.hasNextMediaItem()) active.seekToNextMediaItem() }
    fun prev() { cancelFade(); active.seekToPrevious() }
    fun seekTo(ms: Long) { cancelFade(); active.seekTo(ms) }
    fun jumpTo(index: Int) { cancelFade(); active.seekTo(index, 0); active.play() }

    // ------------------------------------------------------------------ volumen nivelado
    private fun volFor(t: Track?): Float {
        val g = t?.gainDb ?: 0f
        if (!prefs.normalize || g >= 0f) return 1f
        return 10f.pow(g / 20f)
    }

    private fun applyBoost(p: ExoPlayer, t: Track?) {
        val g = t?.gainDb ?: 0f
        val mb = if (prefs.normalize && g > 0f) (g * 100).toInt() else 0
        runCatching { enhancers[p]?.setTargetGain(mb) }
    }

    private fun trackOf(p: ExoPlayer): Track? =
        p.currentMediaItem?.mediaId?.toLongOrNull()?.let { id -> queue.firstOrNull { it.id == id } }

    // ------------------------------------------------------------------ crossfade
    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (e: Exception) { Log.w(TAG, "tick", e) }
            if (++saveTicks >= 40) { saveTicks = 0; if (active.isPlaying) save() }
            handler.postDelayed(this, if (fading) 50 else 250)
        }
    }

    private fun step() {
        if (!fading && dj != null) {
            if (active.isPlaying && active.currentPosition >= djSegEnd) djNext(false)
            return
        }
        if (!fading) {
            if (!prefs.crossfadeOn || !active.isPlaying || active.repeatMode == Player.REPEAT_MODE_ONE) return
            if (!active.hasNextMediaItem()) return
            val d = active.duration
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
        startFadeTo(idx, 0, 1f, (left - 250).coerceAtLeast(500))
    }

    private fun startFadeTo(idx: Int, posMs: Long, speed: Float, lenMs: Long) {
        val out = active; val inc = other
        out.setAudioAttributes(attrs, false)
        inc.setAudioAttributes(attrs, true)
        inc.shuffleModeEnabled = out.shuffleModeEnabled
        inc.repeatMode = out.repeatMode
        inc.seekTo(idx, posMs)
        inc.setPlaybackSpeed(speed)
        inc.volume = 0f
        applyBoost(inc, queue.firstOrNull { it.id.toString() == inc.getMediaItemAt(idx).mediaId })
        inc.prepare()
        inc.play()
        fading = true
        fadeStart = SystemClock.elapsedRealtime()
        fadeLen = lenMs
    }

    // ------------------------------------------------------------------ Modo DJ
    /** Empieza el DJ: canciones al azar de ritmo parecido, desde el coro, con efectos entre canciones. */
    fun startDj(tracks: List<Track>, mood: Mood?, start: Track?, name: String, scores: Map<Long, Float>, plId: Long = -4) {
        if (tracks.isEmpty()) return
        cancelFade()
        val s = DjSession(tracks, mood, scores)
        val first = s.first(start)
        dj = s; djMood = mood
        queue = tracks; playlistId = plId; playlistName = name; showVideo = false
        val items = tracks.map(::item)
        val idx = tracks.indexOfFirst { it.id == first.id }.coerceAtLeast(0)
        other.setAudioAttributes(attrs, false)
        active.setAudioAttributes(attrs, true)
        for (p in listOf(p1, p2)) {
            p.stop(); p.setMediaItems(items, idx, 0)
            p.shuffleModeEnabled = false; p.repeatMode = Player.REPEAT_MODE_OFF; p.setPlaybackSpeed(1f)
        }
        val (st, en) = s.segment(first)
        djSegStart = st; djSegEnd = en; djPending = null
        djInternal = true
        active.seekTo(idx, st)
        djInternal = false
        active.volume = volFor(first); applyBoost(active, first)
        active.prepare(); active.play()
        DjFx.preload(this)
        bump(first); updateButtons(); notifyUi()
    }

    /** Siguiente canción del DJ (sola al llegar a la pausa del coro, o con "Saltar"). */
    fun djNext(skip: Boolean) {
        val s = dj ?: return
        if (fading) completeFade()
        val cur = trackOf(active) ?: return
        val listened = active.currentPosition - djSegStart
        val delta = if (skip && listened < 20_000) -1f else if (!skip) 0.4f else 0f
        if (delta != 0f) App.scope.launch { runCatching { DjBrain.learn(this@PlaybackService, cur.id, djMood, delta) } }
        val (nt, speed) = s.advance(cur)
        val idx = queue.indexOfFirst { it.id == nt.id }
        if (idx < 0) return
        val seg = s.segment(nt)
        DjFx.play(this, prefs.djFxVolume)
        val len = if (prefs.crossfadeOn) (prefs.crossfadeSec * 1000L).coerceIn(1500L, 5000L) else 1500L
        djInternal = true
        startFadeTo(idx, seg.first, speed, len)
        djInternal = false
        djPending = seg
        notifyUi()
    }

    fun exitDj() {
        if (dj == null) return
        dj = null; djMood = null; djPending = null
        for (p in listOf(p1, p2)) { p.setPlaybackSpeed(1f); p.shuffleModeEnabled = prefs.shuffle; p.repeatMode = prefs.repeat }
        updateButtons(); notifyUi()
    }

    private fun completeFade() {
        if (!fading) return
        fading = false
        val out = active
        val inc = other
        out.pause()
        active = inc
        inc.volume = volFor(trackOf(inc))
        session.player = inc
        if (dj != null) djPending?.let { djSegStart = it.first; djSegEnd = it.second; djPending = null }
        updateButtons()
        bump(trackOf(inc)); save()
        notifyUi()
    }

    private fun cancelFade() {
        if (!fading) return
        fading = false
        other.pause()
        other.setAudioAttributes(attrs, false)
        active.setAudioAttributes(attrs, true)
        active.volume = volFor(trackOf(active))
    }

    private inner class PlayerListener(val p: ExoPlayer) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (p === active && fading) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { completeFade(); return }
                if (!djInternal) cancelFade()
            }
            // En el DJ, "siguiente" desde la notificación o los audífonos = saltar con efecto
            if (p === active && dj != null && !djInternal && !fading && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                handler.post { djNext(true) }
            }
            if (p === active) {
                val t = trackOf(p)
                p.volume = volFor(t); applyBoost(p, t)
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) { bump(t); save() }
                notifyUi()
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (p === active) {
                if (!isPlaying && fading && !p.playWhenReady) { other.pause(); cancelFade() }
                if (!isPlaying) save()
                notifyUi()
            }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (p !== active) return
            // Con aleatorio activado la música sigue: al terminar se vuelve a mezclar y continúa.
            if (playbackState == Player.STATE_ENDED && p.shuffleModeEnabled && p.mediaItemCount > 1) {
                val order = smartOrder(queue, -1)
                for (q in listOf(p1, p2)) if (q.mediaItemCount == queue.size) q.setShuffleOrder(order)
                p.seekTo(p.currentTimeline.getFirstWindowIndex(true), 0)
                p.play()
            }
            notifyUi()
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (p === active && other.shuffleModeEnabled != shuffleModeEnabled) {
                prefs.shuffle = shuffleModeEnabled; other.shuffleModeEnabled = shuffleModeEnabled; updateButtons(); notifyUi()
            }
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            if (p === active && other.repeatMode != repeatMode) {
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

/**
 * Nivelar volumen: mide qué tan fuerte suena cada canción (un tramo de ~40 s) y guarda la
 * corrección en dB. Corre en segundo plano, una canción a la vez.
 */
object Loudness {
    private const val TARGET_DB = -17f
    private var job: Job? = null

    fun kick(ctx: Context) {
        Analyzer.kick(ctx)
    }

    @Suppress("unused")
    private fun kickOld(ctx: Context) {
        val app = ctx.applicationContext
        if (job?.isActive == true) return
        job = App.scope.launch {
            val dao = Library.dao(app)
            while (true) {
                val list = dao.needGain()
                if (list.isEmpty()) break
                for (t in list) {
                    val g = runCatching { analyze(app, t.audioUri()) }.getOrNull() ?: 0f
                    dao.track(t.id)?.let { dao.updateTrack(it.copy(gainDb = g)) }
                }
            }
        }
    }

    private fun analyze(ctx: Context, uri: Uri): Float {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return 0f
            val fmt = ex.getTrackFormat(ti)
            ex.selectTrack(ti)
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (dur > 60_000_000L) ex.seekTo(dur / 4, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sum = 0.0; var n = 0L
            var inDone = false
            val startUs = ex.sampleTime
            var float = false
            val limitUs = 40_000_000L
            loop@ while (true) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0 || ex.sampleTime - startUs > limitUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else { codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        if (float) { val fb = ob.asFloatBuffer(); while (fb.hasRemaining()) { val s = fb.get(); sum += s * s; n++ } }
                        else { val sb = ob.asShortBuffer(); while (sb.hasRemaining()) { val s = sb.get() / 32768.0; sum += s * s; n++ } }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                    }
                }
            }
            codec.stop(); codec.release()
            if (n == 0L || sum <= 0) return 0f
            val db = 10 * log10(sum / n).toFloat()
            return (TARGET_DB - db).coerceIn(-12f, 6f)
        } finally { ex.release() }
    }
}

@Suppress("unused") private suspend fun <T> io(block: suspend () -> T) = withContext(Dispatchers.IO) { block() }
