package com.gabo.ytmp3

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * El "oído" de DownSong: analiza cada canción una sola vez (sin internet) y guarda
 * velocidad (BPM), dónde está el coro/clímax, las pausas o bajones, los silencios del
 * inicio y del final, la energía, el brillo y el tipo de música (modelo YAMNet de Google).
 */
object Analyzer {
    private const val TAG = "DSAnalyzer"
    private const val TARGET_DB = -17f
    private var job: Job? = null
    /** (analizadas en esta tanda, pendientes) para la pantalla "Creando tu DJ…". */
    val progress = MutableStateFlow(0 to 0)

    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (job?.isActive == true) return
        job = App.scope.launch {
            val dao = Library.dao(app)
            var done = 0
            while (true) {
                val list = dao.needAnalysis()
                if (list.isEmpty()) break
                for (t in list) {
                    progress.value = done to dao.pendingAnalysis()
                    analyzeAndSave(app, t)
                    done++
                }
            }
            progress.value = done to 0
        }
    }

    suspend fun analyzeAndSave(ctx: Context, t: Track) {
        val dao = Library.dao(ctx)
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { analyze(ctx, t.audioUri()) }.onFailure { Log.w(TAG, "analyze ${t.title}", it) }.getOrNull()
        }
        val cur = dao.track(t.id) ?: return
        dao.updateTrack(if (r == null) cur.copy(analyzed = 2, gainDb = cur.gainDb ?: 0f) else cur.copy(
            analyzed = 1, gainDb = r.gainDb, bpm = r.bpm, climaxMs = r.climaxMs, introMs = r.introMs, outroMs = r.outroMs,
            energy = r.energy, bright = r.bright, tags = r.tags, env = r.env, gaps = r.gaps,
            durationMs = if (cur.durationMs > 0) cur.durationMs else r.durationMs))
    }

    class Result(
        val gainDb: Float, val bpm: Float, val climaxMs: Long, val introMs: Long, val outroMs: Long,
        val energy: Float, val bright: Float, val tags: String?, val env: String, val gaps: String, val durationMs: Long,
    )

    // ------------------------------------------------------------------ YAMNet
    private var yamnet: Interpreter? = null
    private var labels: List<String> = emptyList()

    private fun model(ctx: Context): Interpreter? {
        yamnet?.let { return it }
        return runCatching {
            val fd = ctx.assets.openFd("yamnet.tflite")
            val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            labels = ctx.assets.open("yamnet_class_map.csv").bufferedReader().readLines().drop(1).map { line ->
                line.substringAfter(',').substringAfter(',').trim('"')
            }
            Interpreter(buf, Interpreter.Options().setNumThreads(2)).also { yamnet = it }
        }.onFailure { Log.w(TAG, "yamnet", it) }.getOrNull()
    }

    private val ignore = setOf("Music", "Speech", "Silence", "Musical instrument", "Inside, small room", "Vehicle", "Static", "Noise")

    private fun classify(ctx: Context, pcm16k: ShortArray, n16: Int, startsSec: List<Double>): String? {
        val m = model(ctx) ?: return null
        val win = 15600
        val sum = FloatArray(labels.size.coerceAtLeast(521))
        var runs = 0
        val inShape = m.getInputTensor(0).shape()
        for (s in startsSec) {
            val off = (s * 16000).toInt().coerceIn(0, max(0, n16 - win))
            if (off + win > n16) continue
            val x = FloatArray(win) { pcm16k[off + it] / 32768f }
            val out = Array(1) { FloatArray(521) }
            runCatching {
                if (inShape.size == 1) m.run(x, out) else m.run(arrayOf(x), out)
                for (i in 0 until 521) sum[i] += out[0][i]
                runs++
            }
        }
        if (runs == 0) return null
        return (0 until 521).map { it to sum[it] / runs }
            .filter { labels.getOrNull(it.first)?.let { l -> l !in ignore } == true }
            .sortedByDescending { it.second }.take(10)
            .joinToString("|") { "${labels[it.first]}:${"%.2f".format(java.util.Locale.US, it.second)}" }
    }

    // ------------------------------------------------------------------ análisis de la señal
    fun analyze(ctx: Context, uri: Uri): Result {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val fmt = ex.getTrackFormat(ti)
            ex.selectTrack(ti)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var float = false

            // por cuadro (~23 ms)
            var hop = max(256, rate / 43)
            val rms = FloatArray(40000); val low = FloatArray(40000); val zcr = FloatArray(40000)
            var nf = 0
            var acc = 0.0; var accLow = 0.0; var zc = 0; var cnt = 0
            var lp = 0f; var prev = 0f
            var sumSq = 0.0; var total = 0L
            // 16 kHz mono para YAMNet
            var pcm16 = ShortArray(16000 * 60 * 8)
            var n16 = 0
            var dec = rate / 16000.0; var decAcc = 0.0; var decSum = 0f; var decN = 0
            var inDone = false
            loop@ while (true) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (size < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) { rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); hop = max(256, rate / 43); dec = rate / 16000.0 }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (oi < 0) continue
                val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                ob.position(info.offset); ob.limit(info.offset + info.size)
                val fb = if (float) ob.asFloatBuffer() else null
                val sb = if (!float) ob.asShortBuffer() else null
                val n = (fb?.remaining() ?: sb!!.remaining()) / max(1, ch)
                for (i in 0 until n) {
                    var s = 0f
                    for (c in 0 until ch) s += if (fb != null) fb.get() else sb!!.get() / 32768f
                    s /= ch
                    sumSq += s * s; total++
                    lp += (s - lp) * 0.02f
                    acc += s * s; accLow += lp * lp
                    if ((s >= 0f) != (prev >= 0f)) zc++
                    prev = s
                    if (++cnt >= hop) {
                        if (nf < rms.size) {
                            rms[nf] = sqrt(acc / cnt).toFloat(); low[nf] = sqrt(accLow / cnt).toFloat(); zcr[nf] = zc.toFloat() / cnt
                            nf++
                        }
                        acc = 0.0; accLow = 0.0; zc = 0; cnt = 0
                    }
                    decSum += s; decN++; decAcc += 1.0
                    if (decAcc >= dec) {
                        decAcc -= dec
                        if (n16 >= pcm16.size) pcm16 = pcm16.copyOf(pcm16.size * 2)
                        pcm16[n16++] = ((decSum / decN).coerceIn(-1f, 1f) * 32767).toInt().toShort()
                        decSum = 0f; decN = 0
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
            }
            codec.stop(); codec.release()
            if (nf < 43 * 10) throw IllegalStateException("muy corta")

            val fps = rate.toDouble() / hop
            val msPerFrame = 1000.0 / fps
            val durationMs = (total * 1000.0 / rate).toLong()

            // volumen
            val db = 10 * log10(sumSq / max(1L, total)).toFloat()
            val gain = (TARGET_DB - db).coerceIn(-12f, 6f)

            // silencios del inicio y del final
            val silent = 0.012f
            var first = 0; while (first < nf - 1 && rms[first] < silent) first++
            var last = nf - 1; while (last > 0 && rms[last] < silent) last--
            val introMs = if (first * msPerFrame > 400) ((first - 4).coerceAtLeast(0) * msPerFrame).toLong() else 0L
            val outroMs = if ((nf - 1 - last) * msPerFrame > 600) ((last + 8).coerceAtMost(nf - 1) * msPerFrame).toLong() else 0L

            // BPM: autocorrelación de los golpes (graves + general)
            val onset = FloatArray(nf)
            for (i in 1 until nf) onset[i] = max(0f, low[i] - low[i - 1]) * 2f + max(0f, rms[i] - rms[i - 1])
            val a0 = (nf * 0.2).toInt(); val a1 = min(nf, a0 + (fps * 90).toInt())
            val minLag = (fps * 60 / 185).toInt(); val maxLag = (fps * 60 / 68).toInt()
            val ac = DoubleArray(maxLag + 2)
            for (lag in minLag..maxLag + 1) {
                var s = 0.0
                for (i in a0 until a1 - lag) s += onset[i] * onset[i + lag]
                ac[lag] = s
            }
            var best = minLag
            for (lag in minLag..maxLag) if (ac[lag] > ac[best]) best = lag
            val y0 = ac[best - 1].takeIf { best > minLag } ?: ac[best]; val y2 = ac[best + 1]
            val denom = y0 - 2 * ac[best] + y2
            val frac = if (denom != 0.0) 0.5 * (y0 - y2) / denom else 0.0
            var bpm = (60 * fps / (best + frac.coerceIn(-0.5, 0.5))).toFloat()
            while (bpm < 75f) bpm *= 2f
            while (bpm > 170f) bpm /= 2f

            // energía suavizada (2 s) y clímax: la ventana de 15 s con más energía
            fun smooth(x: FloatArray, w: Int): FloatArray {
                val out = FloatArray(nf); var s = 0.0
                for (i in 0 until nf) {
                    s += x[i]; if (i >= w) s -= x[i - w]
                    out[i] = (s / min(i + 1, w)).toFloat()
                }
                return out
            }
            val e2 = smooth(rms, (fps * 2).toInt())
            val e04 = smooth(rms, (fps * 0.4).toInt())
            val win = (fps * 15).toInt()
            val pre = DoubleArray(nf + 1); for (i in 0 until nf) pre[i + 1] = pre[i] + rms[i]
            val c0 = (nf * 0.12).toInt(); val c1 = (nf * 0.85).toInt() - win
            var bestStart = c0; var bestMean = -1.0
            var i = c0
            while (i < max(c0 + 1, c1)) {
                val m = (pre[min(nf, i + win)] - pre[i]) / win
                if (m > bestMean) { bestMean = m; bestStart = i }
                i += 4
            }
            var climax = bestStart
            val back = (fps * 12).toInt()
            for (j in max(0, bestStart - back) until bestStart) if (e2[j] >= bestMean * 0.82) { climax = j; break }

            // pausas aburridas y bajones (para cambiar de canción justo ahí)
            val sorted = rms.copyOf(nf).sorted()
            val median = sorted[nf / 2]
            val gapsMs = ArrayList<Long>()
            var runStart = -1
            val minRun = (fps * 0.45).toInt()
            val look = (fps * 4).toInt()
            for (k in 0 until nf) {
                val quiet = e04[k] < median * 0.38f
                if (quiet) { if (runStart < 0) runStart = k }
                else {
                    if (runStart >= 0 && k - runStart >= minRun) gapsMs += (runStart * msPerFrame).toLong()
                    runStart = -1
                }
                if (k > look && e2[k] < e2[k - look] * 0.52f && e2[k - look] > median) gapsMs += (k * msPerFrame).toLong()
            }
            val gaps = gapsMs.sorted().fold(ArrayList<Long>()) { acc2, g -> if (acc2.isEmpty() || g - acc2.last() > 5000) acc2.add(g); acc2 }

            // energía media, brillo y silueta de la canción (cada 0,5 s) para el DJ
            val energy = (pre[nf] / nf).toFloat()
            val bright = zcr.take(nf).average().toFloat()
            val step = max(1, (fps * 0.5).toInt())
            val pts = ByteArray(nf / step) { k ->
                var m = 0f; for (q in k * step until min(nf, (k + 1) * step)) m = max(m, rms[q]); (m.coerceIn(0f, 1f) * 255 / max(0.05f, sorted[(nf * 0.98).toInt()])).toInt().coerceIn(0, 255).toByte()
            }
            val env = Base64.encodeToString(pts, Base64.NO_WRAP)

            val climaxSec = climax * msPerFrame / 1000
            val tags = classify(ctx, pcm16, n16, listOf(climaxSec + 2, climaxSec + 8, durationMs / 1000.0 * 0.3))

            return Result(gain, bpm, (climax * msPerFrame).toLong(), introMs, outroMs, energy, bright, tags, env,
                gaps.joinToString(","), durationMs)
        } finally { ex.release() }
    }

    fun envelope(t: Track): ByteArray = t.env?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: ByteArray(0)
    fun gaps(t: Track): List<Long> = t.gaps?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
    fun tag(t: Track, name: String): Float =
        t.tags?.split('|')?.firstOrNull { it.substringBefore(':').equals(name, true) }?.substringAfter(':')?.toFloatOrNull() ?: 0f

    @Suppress("unused") private fun absf(x: Float) = abs(x)
}
