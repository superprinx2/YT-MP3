package com.gabo.ytmp3

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * El "oído" de DownSong: analiza cada canción una sola vez (sin internet) y guarda
 * velocidad (BPM), la rejilla de tiempos, la tonalidad (rueda Camelot), las partes de la
 * canción (intro, versos, coros, final), las pausas, la energía y el tipo de música (YAMNet).
 *
 * Corre en un hilo propio de prioridad baja, poco a poco, mientras escuchas otra cosa:
 * así el Modo DJ arranca al instante.
 */
object Analyzer {
    private const val TAG = "DSAnalyzer"
    private const val TARGET_DB = -17f
    /** Solo se escuchan los primeros 12 minutos (los mixes de horas llenaban la memoria y cerraban la app). */
    private const val MAX_US = 12L * 60 * 1_000_000
    /** Versión del análisis: al subirla, las canciones se vuelven a analizar en segundo plano. */
    const val VER = 2
    private var job: Job? = null
    /** (analizadas en esta tanda, pendientes) para la pantalla "Creando tu DJ…". */
    val progress = MutableStateFlow(0 to 0)
    /** Cada canción recién analizada (el DJ la suma a la mezcla en vivo). */
    val analyzed = MutableSharedFlow<Track>(extraBufferCapacity = 64)

    /** Un solo hilo con prioridad baja: la música y la pantalla nunca se traban. */
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ds-analyzer")
    }.asCoroutineDispatcher()

    private val urgent = LinkedHashSet<Long>()   // el DJ está esperando: sin pausas
    private val soft = LinkedHashSet<Long>()     // la playlist que suena: primero, con calma

    fun needs(t: Track) = t.analyzed == 0 || (t.analyzed == 1 && t.anaVer < VER)

    /** Pone canciones al frente de la fila. [now] = el DJ las espera (sin pausas). */
    fun prioritize(ctx: Context, ids: Collection<Long>, now: Boolean) {
        synchronized(this) { if (now) urgent.addAll(ids) else soft.addAll(ids) }
        kick(ctx)
    }

    private fun take(set: LinkedHashSet<Long>): Long? = synchronized(this) { set.firstOrNull()?.also { set.remove(it) } }

    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (job?.isActive == true) return
        job = App.scope.launch {
            val dao = Library.dao(app)
            // Si la app se cerró mientras analizaba una canción, esa canción se salta para siempre
            val sp = app.getSharedPreferences("analyzer", Context.MODE_PRIVATE)
            val busy = sp.getLong("busy", 0L)
            if (busy != 0L) {
                runCatching { dao.track(busy)?.let { dao.updateTrack(it.copy(analyzed = 2, gainDb = it.gainDb ?: 0f)) } }
                sp.edit().putLong("busy", 0L).commit()
                BlackBox.log(app, "análisis saltado: la canción $busy cerró la app la vez anterior")
            }
            var done = 0
            var hot = 0
            while (true) {
                var hurry = false
                var t: Track? = null
                while (t == null) {
                    val id = take(urgent)?.also { hurry = true } ?: take(soft) ?: break
                    t = dao.track(id)?.takeIf(::needs)
                }
                if (t == null) t = dao.needAnalysis().firstOrNull()
                if (t == null) break
                if (!hurry) {
                    // Ahorro: solo con el cargador puesto. Con música sonando: con calma.
                    if (Prefs2(app).saver && !Device.battery(app).charging) break
                    if (PlaybackService.playingNow) delay(2500)
                    if (Device.thermal(app) >= 3) { if (++hot > 20) break; delay(30_000); continue }
                }
                progress.value = done to dao.pendingAnalysis()
                analyzeAndSave(app, t)
                done++
            }
            progress.value = done to 0
        }
    }

    suspend fun analyzeAndSave(ctx: Context, t: Track) {
        val dao = Library.dao(ctx)
        val sp = ctx.applicationContext.getSharedPreferences("analyzer", Context.MODE_PRIVATE)
        sp.edit().putLong("busy", t.id).commit()
        val r = withContext(dispatcher) {
            try { analyze(ctx, t.audioUri()) }
            catch (e: Throwable) { Log.w(TAG, "analyze ${t.title}", e); BlackBox.log(ctx, "no se pudo analizar «${t.title.take(40)}»: ${e.javaClass.simpleName}"); null }
        }
        sp.edit().putLong("busy", 0L).apply()
        val cur = dao.track(t.id) ?: return
        val upd = if (r == null) cur.copy(analyzed = 2, gainDb = cur.gainDb ?: 0f) else cur.copy(
            analyzed = 1, anaVer = VER, gainDb = r.gainDb, bpm = r.bpm, climaxMs = r.climaxMs, introMs = r.introMs, outroMs = r.outroMs,
            energy = r.energy, bright = r.bright, tags = r.tags, env = r.env, gaps = r.gaps,
            keyCam = r.keyCam, beatMs = r.beatMs, sections = r.sections,
            durationMs = if (r.durationMs > 0) r.durationMs else cur.durationMs)
        dao.updateTrack(upd)
        if (r != null) analyzed.tryEmit(upd)
    }

    class Result(
        val gainDb: Float, val bpm: Float, val climaxMs: Long, val introMs: Long, val outroMs: Long,
        val energy: Float, val bright: Float, val tags: String?, val env: String, val gaps: String, val durationMs: Long,
        val keyCam: String?, val beatMs: Long, val sections: String,
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
            Interpreter(buf, Interpreter.Options().setNumThreads(1)).also { yamnet = it }
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
            // 16 kHz mono para YAMNet y la armonía
            var pcm16 = ShortArray(16000 * 60 * 8)
            var n16 = 0
            var dec = rate / 16000.0; var decAcc = 0.0; var decSum = 0f; var decN = 0
            var inDone = false
            var capped = false
            val realDurMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            try {
                loop@ while (true) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(5000)
                        if (ii >= 0) {
                            val size = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                            if (size >= 0 && ex.sampleTime > MAX_US) capped = true
                            if (size < 0 || capped) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
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
                            if (n16 >= pcm16.size) pcm16 = pcm16.copyOf(minOf(pcm16.size * 2, 16000 * 60 * 13))
                            if (n16 >= pcm16.size) continue
                            pcm16[n16++] = ((decSum / decN).coerceIn(-1f, 1f) * 32767).toInt().toShort()
                            decSum = 0f; decN = 0
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            } finally { runCatching { codec.stop() }; codec.release() }
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

            // Rejilla de tiempos: dónde cae el primer golpe y cuál es el tiempo fuerte del compás
            val period = fps * 60.0 / bpm
            val lowOn = FloatArray(nf); for (q in 1 until nf) lowOn[q] = max(0f, low[q] - low[q - 1])
            var bestPh = 0.0; var bestSc = -1.0
            var ph = 0.0
            while (ph < period) {
                var sc = 0.0; var k = ph + a0
                while (k < a1) { sc += onset[k.toInt().coerceIn(0, nf - 1)]; k += period }
                if (sc > bestSc) { bestSc = sc; bestPh = ph }
                ph += 0.5
            }
            var bestBar = 0; var barSc = -1.0
            for (bb in 0 until 4) {
                var sc = 0.0; var k = bestPh + bb * period + a0
                while (k < a1) { sc += lowOn[k.toInt().coerceIn(0, nf - 1)]; k += period * 4 }
                if (sc > barSc) { barSc = sc; bestBar = bb }
            }
            val down = (bestPh + bestBar * period + a0) % (period * 4)
            val beatMs = (down * msPerFrame).toLong()

            // Tonalidad (rueda Camelot) y "color armónico" cada 0,5 s
            val chroma = chromaBlocks(pcm16, n16)
            val keyCam = keyOf(chroma)

            // Partes de la canción: dónde cambia el sonido (verso, coro, puente…)
            val sections = runCatching { sectionsOf(chroma, rms, nf, fps, beatMs, 60000.0 / bpm, durationMs) }
                .onFailure { Log.w(TAG, "sections", it) }.getOrDefault("")

            return Result(gain, bpm, (climax * msPerFrame).toLong(), introMs, if (capped) 0L else outroMs, energy, bright, tags, env,
                gaps.joinToString(","), if (capped && realDurMs > 0) realDurMs else durationMs, keyCam, beatMs, sections)
        } finally { ex.release() }
    }

    // ------------------------------------------------------------------ armonía y estructura
    private const val BLOCK = 8000   // 0,5 s a 16 kHz
    private const val NFFT = 4096

    /** Energía por nota (do, do#, re…) cada 0,5 s, normalizada; el índice 12 es el volumen. */
    private fun chromaBlocks(pcm: ShortArray, n: Int): Array<FloatArray> {
        val blocks = n / BLOCK
        val out = Array(blocks) { FloatArray(13) }
        val re = FloatArray(NFFT); val im = FloatArray(NFFT)
        val win = FloatArray(NFFT) { (0.5 - 0.5 * cos(2 * Math.PI * it / (NFFT - 1))).toFloat() }
        val pc = IntArray(NFFT / 2) { k ->
            val f = k * 16000.0 / NFFT
            if (f < 80 || f > 2500) -1 else ((Math.round(12 * ln(f / 440.0) / ln(2.0)) + 9 + 1200) % 12).toInt()
        }
        for (b in 0 until blocks) {
            val off = b * BLOCK + (BLOCK - NFFT) / 2
            var e = 0f
            for (i in 0 until NFFT) { val v = pcm[(off + i).coerceIn(0, n - 1)] / 32768f; re[i] = v * win[i]; im[i] = 0f; e += v * v }
            fft(re, im)
            val c = out[b]
            for (k in 1 until NFFT / 2) { val p = pc[k]; if (p >= 0) c[p] += sqrt(re[k] * re[k] + im[k] * im[k]) }
            var m = 0f; for (q in 0 until 12) m = max(m, c[q])
            if (m > 0f) for (q in 0 until 12) c[q] /= m
            c[12] = sqrt(e / NFFT)
        }
        return out
    }

    private val majorProfile = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minorProfile = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val camMajor = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
    private val camMinor = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

    /** Tonalidad por el método de Krumhansl, en código Camelot ("8A", "11B"…). */
    private fun keyOf(ch: Array<FloatArray>): String? {
        if (ch.isEmpty()) return null
        val sum = DoubleArray(12)
        for (c in ch) { val w = c[12].toDouble(); for (q in 0 until 12) sum[q] += c[q] * w }
        val mx = sum.average()
        fun corr(p: DoubleArray, r: Int): Double {
            val mp = p.average()
            var num = 0.0; var dx = 0.0; var dp = 0.0
            for (q in 0 until 12) { val x = sum[(q + r) % 12] - mx; val y = p[q] - mp; num += x * y; dx += x * x; dp += y * y }
            return if (dx <= 0 || dp <= 0) -1.0 else num / sqrt(dx * dp)
        }
        var bestC = -2.0; var res: String? = null
        for (r in 0 until 12) {
            val a = corr(majorProfile, r); if (a > bestC) { bestC = a; res = "${camMajor[r]}B" }
            val b = corr(minorProfile, r); if (b > bestC) { bestC = b; res = "${camMinor[r]}A" }
        }
        return if (bestC < 0.3) null else res
    }

    /**
     * Divide la canción en partes donde cambia el sonido (novedad sobre la matriz de parecido),
     * las ajusta al compás y marca cuáles se repiten con fuerza (coros).
     * Formato: "inicio:fin:energía(0-9):tipo" separados por ";" (I intro, V verso, C coro, O final).
     */
    private fun sectionsOf(ch: Array<FloatArray>, rms: FloatArray, nf: Int, fps: Double,
                           beatMs: Long, beatLen: Double, durMs: Long): String {
        val n = ch.size
        if (n < 40) return ""
        val framesPerBlock = fps * 0.5
        val feat = Array(n) { b ->
            val f = FloatArray(14)
            for (q in 0 until 12) f[q] = ch[b][q]
            var e = 0f; var cnt = 0
            var q = (b * framesPerBlock).toInt()
            while (q < min(nf, ((b + 1) * framesPerBlock).toInt())) { e += rms[q]; cnt++; q++ }
            f[12] = if (cnt > 0) e / cnt else 0f
            f
        }
        var eMax = 0.0001f; for (f in feat) eMax = max(eMax, f[12])
        for (f in feat) { f[12] = f[12] / eMax * 2.5f; f[13] = f[12] }
        val norm = FloatArray(n) { b -> var s = 0f; for (q in 0 until 14) s += feat[b][q] * feat[b][q]; sqrt(s) }
        fun sim(a: Int, b: Int): Float {
            if (norm[a] <= 0f || norm[b] <= 0f) return 0f
            var d = 0f; for (q in 0 until 14) d += feat[a][q] * feat[b][q]
            return d / (norm[a] * norm[b])
        }
        // novedad con núcleo de tablero de ajedrez (8 s a cada lado)
        val kL = 16
        val nov = FloatArray(n)
        for (i in kL until n - kL) {
            var s = 0f
            for (a in -kL until kL step 2) for (b in -kL until kL step 2) {
                val sign = if ((a < 0) == (b < 0)) 1f else -1f
                s += sign * sim(i + a, i + b)
            }
            nov[i] = max(0f, -s)
        }
        val mean = nov.average().toFloat()
        var sd = 0.0; for (v in nov) sd += (v - mean) * (v - mean); sd = sqrt(sd / n)
        val th = mean + 0.4f * sd.toFloat()
        val cuts = ArrayList<Int>()
        for (i in 2 until n - 2) {
            if (nov[i] < th) continue
            var peak = true
            for (j in max(0, i - 12)..min(n - 1, i + 12)) if (nov[j] > nov[i]) { peak = false; break }
            if (peak && (cuts.isEmpty() || i - cuts.last() >= 16)) cuts += i
        }
        // a milisegundos, ajustados al compás (4 tiempos)
        val bar = beatLen * 4
        fun snap(ms: Double): Long = if (bar <= 0) ms.toLong() else (beatMs + Math.round((ms - beatMs) / bar) * bar).toLong()
        val bounds = ArrayList<Long>()
        bounds += 0L
        for (c in cuts) { val ms = snap(c * 500.0); if (ms > bounds.last() + 6000 && ms < durMs - 6000) bounds += ms }
        bounds += durMs
        class Sec(val s: Long, val e: Long, val en: Float, val chroma: FloatArray)
        val secs = (0 until bounds.size - 1).map { k ->
            val b0 = (bounds[k] / 500).toInt().coerceIn(0, n - 1); val b1 = (bounds[k + 1] / 500).toInt().coerceIn(b0 + 1, n)
            val cm = FloatArray(12); var en = 0f
            for (b in b0 until b1) { for (q in 0 until 12) cm[q] += feat[b][q]; en += feat[b][12] }
            Sec(bounds[k], bounds[k + 1], en / (b1 - b0), cm)
        }
        val ens = secs.map { it.en }.sorted()
        fun rank(e: Float): Int = (ens.indexOfFirst { it >= e }.coerceAtLeast(0) * 9 / max(1, ens.size - 1)).coerceIn(0, 9)
        val med = ens[ens.size / 2]
        fun cosSim(a: FloatArray, b: FloatArray): Float {
            var d = 0f; var na = 0f; var nb = 0f
            for (q in 0 until 12) { d += a[q] * b[q]; na += a[q] * a[q]; nb += b[q] * b[q] }
            return if (na <= 0 || nb <= 0) 0f else d / sqrt(na * nb)
        }
        return secs.mapIndexed { k, x ->
            val repeats = secs.indices.any { j -> abs(j - k) > 1 && cosSim(x.chroma, secs[j].chroma) > 0.93f }
            val type = when {
                k == 0 && x.en < med -> "I"
                k == secs.size - 1 && x.en < med -> "O"
                x.en >= med && repeats -> "C"
                else -> "V"
            }
            "${x.s}:${x.e}:${rank(x.en)}:$type"
        }.joinToString(";")
    }

    private fun fft(x: FloatArray, y: FloatArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = x[i]; x[i] = x[j]; x[j] = t; t = y[i]; y[i] = y[j]; y[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val vr = x[b] * cr - y[b] * ci; val vi = x[b] * ci + y[b] * cr
                    x[b] = x[a] - vr; y[b] = y[a] - vi; x[a] += vr; y[a] += vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Una parte de la canción (intro, verso, coro o final). */
    class Part(val start: Long, val end: Long, val energy: Int, val type: Char) { val len get() = end - start }

    fun parts(t: Track): List<Part> = t.sections?.takeIf { it.isNotEmpty() }?.split(';')?.mapNotNull { p ->
        val f = p.split(':')
        if (f.size < 4) null else runCatching { Part(f[0].toLong(), f[1].toLong(), f[2].toInt(), f[3][0]) }.getOrNull()
    }.orEmpty()

    fun envelope(t: Track): ByteArray = t.env?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: ByteArray(0)
    fun gaps(t: Track): List<Long> = t.gaps?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
    fun tag(t: Track, name: String): Float =
        t.tags?.split('|')?.firstOrNull { it.substringBefore(':').equals(name, true) }?.substringAfter(':')?.toFloatOrNull() ?: 0f
}
