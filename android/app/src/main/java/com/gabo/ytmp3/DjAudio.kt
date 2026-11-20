package com.gabo.ytmp3

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.tan

/**
 * La "consola" de cada plato del DJ, como un mezclador de verdad:
 * - Ecualizador de 3 bandas (graves, medios/voces, agudos) para el cambio de bajos y que no choquen dos voces.
 * - Filtros que barren (pasa-bajos y pasa-altos).
 * - Eco a tempo con la cola sin graves (no embarra la mezcla).
 * - Loop a tempo: repite el último compás y lo va acortando (1 compás → 2 tiempos → 1 → ½) para crecer hasta el drop.
 * Todo sale de la propia canción: ningún sonido agregado. Si nada está activo, deja pasar el sonido tal cual.
 */
@OptIn(UnstableApi::class)
class DjProcessor : BaseAudioProcessor() {
    /** Bandas del ecualizador (1 = normal, 0 = cortada). */
    @Volatile var low = 1f
    @Volatile var mid = 1f
    @Volatile var high = 1f
    /** Filtro pasa-bajos (20000 = apagado). */
    @Volatile var lpHz = 20000f
    /** Filtro pasa-altos (0 = apagado). */
    @Volatile var hpHz = 0f
    /** Sonido directo (0 = solo queda el eco o el loop). */
    @Volatile var dry = 1f
    /** Cuánto sonido entra al eco. */
    @Volatile var send = 0f
    @Volatile var delayMs = 450f
    @Volatile var feedback = 0f
    /** Loop a tempo: largo del loop en ms de la canción (0 = apagado). */
    @Volatile var loopMs = 0f

    private var rate = 44100
    private var ch = 2
    // cruce Linkwitz-Riley de 4.º orden (como los mezcladores de DJ): dos filtros en cascada por banda
    private val loA = Svf(); private val loB = Svf(); private val reA = Svf(); private val reB = Svf()
    private val miA = Svf(); private val miB = Svf(); private val hiA = Svf(); private val hiB = Svf()
    private val lp1 = FloatArray(8); private val lp2 = FloatArray(8)
    private val hp1 = FloatArray(8); private val hp2 = FloatArray(8)
    private val echoHp = FloatArray(8)
    private var echo = FloatArray(0)
    private var echoPos = 0
    private var tail = 0
    // loop
    private var ring = FloatArray(0)
    private var ringPos = 0
    private var looping = false
    private var loopStart = 0
    private var loopIdx = 0

    fun resetFx() { low = 1f; mid = 1f; high = 1f; lpHz = 20000f; hpHz = 0f; dry = 1f; send = 0f; feedback = 0f; loopMs = 0f }

    private val idle get() = low > 0.999f && mid > 0.999f && high > 0.999f && lpHz >= 19000f && hpHz < 1f &&
        dry > 0.999f && send <= 0f && tail <= 0 && loopMs <= 0f && !looping

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        rate = inputAudioFormat.sampleRate; ch = inputAudioFormat.channelCount.coerceIn(1, 8)
        echo = FloatArray(rate * 2 * ch)
        ring = FloatArray(rate * 7 * ch)
        return inputAudioFormat
    }

    private val k = 1.4142f
    private fun coef(fc: Float): FloatArray {
        val g = tan(PI * (fc.coerceIn(20f, rate * 0.45f)) / rate).toFloat()
        val a1 = 1f / (1f + g * (g + k)); val a2 = g * a1; val a3 = g * a2
        return floatArrayOf(a1, a2, a3)
    }
    private var cEqLRate = 0
    private var cEqLc = FloatArray(3); private var cEqHc = FloatArray(3)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val inp = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = n / (2 * ch)
        val ringLen = ring.size
        if (idle) {
            // guardar lo que suena (para poder hacer loop en cualquier momento) y dejarlo pasar
            val start = inp.position()
            for (i in 0 until frames * ch) { ring[ringPos] = inp.getShort(start + i * 2) / 32768f; ringPos = (ringPos + 1) % ringLen }
            out.put(inp); out.flip(); return
        }
        out.order(ByteOrder.LITTLE_ENDIAN)
        if (cEqLRate != rate) { cEqLc = coef(250f); cEqHc = coef(3500f); cEqLRate = rate }
        val gL = low; val gM = mid; val gH = high
        val useEq = gL < 0.999f || gM < 0.999f || gH < 0.999f
        val dryG = dry; val sendG = send; val fb = feedback
        val cl = if (lpHz < 19000f) coef(lpHz) else null
        val chp = if (hpHz >= 1f) coef(hpHz) else null
        val dSamples = ((delayMs / 1000f) * rate).toInt().coerceIn(1, rate * 2 - 1) * ch
        val aEcho = 1f - exp(-2f * PI.toFloat() * 300f / rate)
        // loop a tempo
        val lms = loopMs
        if (lms > 0f && !looping) {
            val len = ((lms / 1000f) * rate).toInt() * ch
            if (len in (ch * 64) until ringLen - rate * ch) {
                looping = true
                loopStart = ((ringPos - len) % ringLen + ringLen) % ringLen
                loopIdx = 0
            }
        } else if (lms <= 0f && looping) looping = false
        val curLen = if (looping) (((lms / 1000f) * rate).toInt() * ch).coerceAtLeast(ch * 64) else 0
        val fade = (rate / 200) * ch   // 5 ms de cruce para que el loop no haga clic
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                val raw = inp.short / 32768f
                var x: Float
                if (looping) {
                    if (loopIdx >= curLen) loopIdx %= curLen
                    x = ring[(loopStart + loopIdx) % ringLen]
                    val left = curLen - loopIdx
                    if (left <= fade) {
                        // mezclar el final del loop con lo que venía justo antes de su inicio
                        val a = left.toFloat() / fade
                        val pre = ring[((loopStart - left) % ringLen + ringLen) % ringLen]
                        x = x * a + pre * (1f - a)
                    }
                    loopIdx++
                } else {
                    x = raw
                    ring[ringPos] = raw; ringPos = (ringPos + 1) % ringLen
                }
                if (useEq) {
                    // graves < 250 Hz, agudos > 3,5 kHz, el resto son medios (voces)
                    val lo = loB.lp(loA.lp(x, c, cEqLc), c, cEqLc)
                    val rest = reB.hp(reA.hp(x, c, cEqLc), c, cEqLc)
                    val md = miB.lp(miA.lp(rest, c, cEqHc), c, cEqHc)
                    val hi = hiB.hp(hiA.hp(rest, c, cEqHc), c, cEqHc)
                    x = lo * gL + md * gM + hi * gH
                }
                if (cl != null) {
                    val v3 = x - lp2[c]; val v1 = cl[0] * lp1[c] + cl[1] * v3; val v2 = lp2[c] + cl[1] * lp1[c] + cl[2] * v3
                    lp1[c] = 2 * v1 - lp1[c]; lp2[c] = 2 * v2 - lp2[c]; x = v2
                }
                if (chp != null) {
                    val v3 = x - hp2[c]; val v1 = chp[0] * hp1[c] + chp[1] * v3; val v2 = hp2[c] + chp[1] * hp1[c] + chp[2] * v3
                    hp1[c] = 2 * v1 - hp1[c]; hp2[c] = 2 * v2 - hp2[c]; x = x - k * v1 - v2
                }
                var y = x * dryG
                if (sendG > 0f || tail > 0) {
                    val rd = (echoPos - dSamples + echo.size) % echo.size
                    val d = echo[rd]
                    y += d
                    // la cola del eco sin graves: suena limpia y no embarra
                    val sIn = x * sendG + d * fb
                    echoHp[c] += aEcho * (sIn - echoHp[c])
                    echo[echoPos] = sIn - echoHp[c]
                    echoPos = (echoPos + 1) % echo.size
                }
                out.putShort((y.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        if (sendG > 0f) tail = rate * 4 * ch else if (tail > 0) tail = (tail - frames * ch).coerceAtLeast(0)
        if (tail == 0 && sendG <= 0f) echo.fill(0f)
        out.flip()
    }

    override fun onFlush() {
        listOf(loA, loB, reA, reB, miA, miB, hiA, hiB).forEach { it.clear() }
        lp1.fill(0f); lp2.fill(0f); hp1.fill(0f); hp2.fill(0f); echoHp.fill(0f)
        echo.fill(0f); tail = 0; looping = false
    }
    override fun onReset() { onFlush(); resetFx() }

    /** Filtro de estado variable (TPT), Butterworth: pasa-bajos o pasa-altos por canal. */
    private class Svf {
        val s1 = FloatArray(8); val s2 = FloatArray(8)
        fun clear() { s1.fill(0f); s2.fill(0f) }
        fun lp(x: Float, c: Int, k3: FloatArray): Float {
            val v3 = x - s2[c]; val v1 = k3[0] * s1[c] + k3[1] * v3; val v2 = s2[c] + k3[1] * s1[c] + k3[2] * v3
            s1[c] = 2 * v1 - s1[c]; s2[c] = 2 * v2 - s2[c]; return v2
        }
        fun hp(x: Float, c: Int, k3: FloatArray): Float {
            val v3 = x - s2[c]; val v1 = k3[0] * s1[c] + k3[1] * v3; val v2 = s2[c] + k3[1] * s1[c] + k3[2] * v3
            s1[c] = 2 * v1 - s1[c]; s2[c] = 2 * v2 - s2[c]; return x - 1.4142f * v1 - v2
        }
    }
}

/**
 * "Caja negra": anota cuándo y por qué se cortó o cambió la música (sin datos personales),
 * para poder encontrar la causa exacta. Se envía desde Ajustes → Acerca de.
 */
object BlackBox {
    private const val MAX = 256 * 1024
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    private fun file(ctx: Context) = File(ctx.filesDir, "caja_negra.txt")

    @Synchronized fun log(ctx: Context, msg: String) {
        runCatching {
            val f = file(ctx)
            if (f.length() > MAX) { val keep = f.readText().takeLast(MAX / 2); f.writeText(keep) }
            f.appendText("${fmt.format(Date())}  $msg\n")
        }
    }

    fun share(ctx: Context) {
        val f = file(ctx)
        val text = buildString {
            append("DownSong ${BuildConfigInfo.version(ctx)} · Android ${android.os.Build.VERSION.RELEASE} · ")
            append("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n")
            append(if (f.exists()) f.readText().takeLast(60_000) else "Sin cortes anotados.")
        }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Informe de cortes de DownSong")
            .putExtra(Intent.EXTRA_TEXT, text), "Enviar informe").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object BuildConfigInfo {
    fun version(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}

