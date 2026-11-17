package com.gabo.ytmp3

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.audiofx.LoudnessEnhancer
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
        session = MediaSession.Builder(this, active)
            .setSessionActivity(open)
            .setCallback(SessionCallback())
            .build()
        updateButtons()
        instance = this
        handler.post(tick)
        notifyUi()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!active.playWhenReady || active.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
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
        return MediaItem.Builder().setMediaId(t.id.toString()).setUri(uri)
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
            p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(items.size, seed))
        }
        active.volume = volFor(tracks[idx])
        applyBoost(active, tracks[idx])
        active.prepare()
        active.play()
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
    fun next() { cancelFade(); if (active.hasNextMediaItem()) active.seekToNextMediaItem() }
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
            handler.postDelayed(this, if (fading) 50 else 250)
        }
    }

    private fun step() {
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
        val out = active; val inc = other
        val idx = out.nextMediaItemIndex
        if (idx == C.INDEX_UNSET) return
        out.setAudioAttributes(attrs, false)
        inc.setAudioAttributes(attrs, true)
        inc.shuffleModeEnabled = out.shuffleModeEnabled
        inc.repeatMode = out.repeatMode
        inc.seekTo(idx, 0)
        inc.volume = 0f
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
        val out = active
        val inc = other
        out.pause()
        active = inc
        inc.volume = volFor(trackOf(inc))
        session.player = inc
        updateButtons()
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
                cancelFade()
            }
            if (p === active) {
                val t = trackOf(p)
                p.volume = volFor(t); applyBoost(p, t)
                notifyUi()
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (p === active) {
                if (!isPlaying && fading && !p.playWhenReady) { other.pause(); cancelFade() }
                notifyUi()
            }
        }
        override fun onPlaybackStateChanged(playbackState: Int) { if (p === active) notifyUi() }
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
