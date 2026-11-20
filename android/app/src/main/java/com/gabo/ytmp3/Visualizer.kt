package com.gabo.ytmp3

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.Choreographer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Datos del sonido para los visualizadores (los escribe el reproductor). */
object AudioData {
    val wave = FloatArray(256)
    val spec = FloatArray(64)
    private val ring = FloatArray(512)
    private var pos = 0
    private val re = FloatArray(512); private val im = FloatArray(512)
    private val window = FloatArray(512) { (0.5 - 0.5 * cos(2 * PI * it / 511)).toFloat() }

    /** Se llama desde el hilo de audio con cada muestra (mono, -1..1). */
    fun push(s: Float) {
        ring[pos++] = s
        if (pos == 512) { pos = 0; analyze() }
    }

    private fun analyze() {
        for (i in 0 until 256) wave[i] = ring[i * 2]
        for (i in 0 until 512) { re[i] = ring[i] * window[i]; im[i] = 0f }
        fft(re, im)
        // 64 bandas logarítmicas (de graves a agudos)
        for (b in 0 until 64) {
            val lo = (1.5 * Math.pow(170.0, b / 64.0)).toInt().coerceIn(1, 255)
            val hi = (1.5 * Math.pow(170.0, (b + 1) / 64.0)).toInt().coerceIn(lo + 1, 256)
            var m = 0f
            for (k in lo until hi) m = max(m, sqrt(re[k] * re[k] + im[k] * im[k]))
            val v = (ln(1f + m * 4f) / 3f).coerceIn(0f, 1.2f)
            spec[b] = spec[b] * 0.55f + v * 0.45f
        }
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
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = x[i + k]; val ui = y[i + k]
                    val vr = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci
                    val vi = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k] = ur + vr; y[i + k] = ui + vi
                    x[i + k + len / 2] = ur - vr; y[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Visualizador de órbitas (OpenGL): un túnel 3D de fractales de puntos luminosos que vuela con
 * la música y nunca se repite. Cada "capa" es un fractal de órbitas (fórmula Hopalong de Barry
 * Martin, 1986, generalizada) con números al azar; al pasar la cámara vuelve al fondo con una
 * figura nueva. La música pinta la profundidad: las capas cercanas brillan con los bajos y las
 * lejanas con los agudos. Encima: estela, brillo difuso (bloom), espejos de caleidoscopio y filtros.
 * Escenas al azar (formas de luz, colores, giro, velocidad, espejos) que cambian con los golpes.
 * Técnicas inspiradas en AudiOrbits; código propio de DownSong.
 */
class VizView(ctx: Context) : GLSurfaceView(ctx) {
    private val renderer = VizRenderer(Prefs2(ctx).usage)
    /** Color base por estado de ánimo (null = colores libres). */
    var hue: Float? = null
        set(v) { field = v; renderer.baseHue = v }
    /** (Compatibilidad) silueta y avance de la canción. */
    var envelope: ByteArray = ByteArray(0)
    var progress: Float = 0f
    /** Modo DJ: escenas más explosivas y cambios más seguidos. */
    var djStyle: Boolean = false
        set(v) { field = v; renderer.djStyle = v }
    private var fps = 30
    private var running = false
    private var lastFrame = 0L

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        fps = when (Prefs2(ctx).usage) { UsageMode.MAX -> 60; UsageMode.BALANCED -> 45; UsageMode.SAVER -> 30 }
    }

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            val ms = now / 1_000_000
            if (ms - lastFrame >= 1000 / fps - 2) { lastFrame = ms; requestRender() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() { if (running) return; running = true; onResume(); Choreographer.getInstance().postFrameCallback(tick) }
    fun stop() { if (!running) return; running = false; Choreographer.getInstance().removeFrameCallback(tick); onPause() }
    fun skipPreset() { renderer.nextPreset() }
    /** Color que más se ve ahora en la animación (para el aro del disco del DJ). 0 = aún no se sabe. */
    val dominant: Int get() = renderer.dominant

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}

/** Generador de fractales de órbitas (en segundo plano, un hilo para todos los visualizadores). */
private object OrbitGen {
    val exec: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); r.run() }, "ds-orbits")
    }

    /** Parámetros de forma de una escena. */
    class Shape(val aMin: Float, val aMax: Float, val bMin: Float, val bMax: Float, val cMin: Float, val cMax: Float,
                val dMin: Float, val dMax: Float, val eMin: Float, val eMax: Float, val tunnelIn: Float, val tunnelOut: Float)

    /** Una capa: [subsets] grupos de [points] puntos (x, y en -1..1). */
    fun level(sh: Shape, subsets: Int, points: Int, rnd: Random): FloatArray {
        val out = FloatArray(subsets * points * 2)
        fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        val a = r(sh.aMin, sh.aMax); val b = r(sh.bMin, sh.bMax); val c = r(sh.cMin, sh.cMax)
        val d = r(sh.dMin, sh.dMax); val e = r(sh.eMin, sh.eMax)
        val kind = rnd.nextFloat()
        var xMin = Float.MAX_VALUE; var xMax = -Float.MAX_VALUE; var yMin = Float.MAX_VALUE; var yMax = -Float.MAX_VALUE
        for (s in 0 until subsets) {
            // cada grupo arranca en un punto distinto: así se "replica" la figura en el espacio
            var x = s / 100f * (0.5f - rnd.nextFloat())
            var y = s / 100f * (0.5f - rnd.nextFloat())
            for (i in 0 until points) {
                val q = abs(b * x - c)
                val z = d + when {
                    kind < 0.5f -> sqrt(q)
                    kind < 0.75f -> sqrt(sqrt(q))
                    else -> ln(2f + sqrt(q))
                }
                val x1 = if (x > 0f) y - z else if (x == 0f) y else y + z
                y = a - x
                x = x1 + e
                val k = (s * points + i) * 2
                out[k] = x; out[k + 1] = y
                if (x < xMin) xMin = x; if (x > xMax) xMax = x
                if (y < yMin) yMin = y; if (y > yMax) yMax = y
            }
        }
        val sx = 2f / max(1e-4f, xMax - xMin); val sy = 2f / max(1e-4f, yMax - yMin)
        val ir = sh.tunnelIn; val or = max(0.01f, sh.tunnelOut)
        var k = 0
        while (k < out.size) {
            var x = (out[k] - xMin) * sx - 1f
            var y = (out[k + 1] - yMin) * sy - 1f
            // "túnel": saca los puntos del centro para que la cámara pase sin chocar
            if (ir > 0f) {
                val dist = sqrt(x * x + y * y)
                if (dist < ir && dist > 1e-5f) {
                    val sc = dist / ir; val outer = sc / or
                    x = x / sc + x * outer; y = y / sc + y * outer
                }
            }
            out[k] = x; out[k + 1] = y
            k += 2
        }
        return out
    }
}

private class VizRenderer(usage: UsageMode) : GLSurfaceView.Renderer {
    @Volatile var baseHue: Float? = null
    @Volatile var djStyle = false
    @Volatile var dominant = 0
    @Volatile private var force = false
    fun nextPreset() { force = true }

    // calidad según el modo de uso
    private val levels = when (usage) { UsageMode.MAX -> 6; UsageMode.BALANCED -> 5; UsageMode.SAVER -> 4 }
    private val subsets = when (usage) { UsageMode.MAX -> 12; UsageMode.BALANCED -> 10; UsageMode.SAVER -> 8 }
    private val points = when (usage) { UsageMode.MAX -> 1600; UsageMode.BALANCED -> 1000; UsageMode.SAVER -> 600 }
    private val scale = when (usage) { UsageMode.MAX -> 0.75f; UsageMode.BALANCED -> 0.6f; UsageMode.SAVER -> 0.45f }
    private val depth = 2f           // profundidad de cada capa
    private val near = 0.06f

    private val rnd = Random(System.nanoTime())
    private var w = 1; private var h = 1; private var fw = 1; private var fh = 1; private var bw = 1; private var bh = 1
    private var progPts = 0; private var progTrail = 0; private var progBright = 0; private var progBlur = 0; private var progShow = 0
    private lateinit var quad: FloatBuffer
    private val vbo = IntArray(6)
    private val ready = BooleanArray(6)
    private val z = Array(6) { FloatArray(12) }
    private val moved = IntArray(6)
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, FloatArray>>()
    private val requested = BooleanArray(6)
    // FBO: escena, estela (2), brillo (2)
    private val tex = IntArray(5); private val fbo = IntArray(5)
    private var trailSrc = 1
    private var frameN = 0
    private val pix: ByteBuffer = ByteBuffer.allocateDirect(24 * 24 * 4).order(ByteOrder.nativeOrder())
    private var last = SystemClock.uptimeMillis()
    private val t0 = SystemClock.uptimeMillis()

    // audio
    private var level = 0f; private var bass = 0f; private var bassAvg = 0.1f; private var beat = 0f
    private var lowAvg = 0.1f; private var lowPrev = 0f
    private var flash = 0f; private var hueKick = 0f; private var rotKick = 0f; private var speedKick = 0f
    private var angle = 0f; private var spin = 0f

    /** Una escena: forma de las órbitas, vuelo, luces, colores y filtros. */
    private class Scene(
        val shape: OrbitGen.Shape, val spread: Float, val fov: Float, val zoom: Float, val rot: Float,
        val style: Int, val size: Float, val colorMode: Int, val fade: Float, val hueA: Float, val hueB: Float,
        val segs: Float, val trail: Float, val bloom: Float, val filter: Int, val audioZoom: Float, val name: String,
    )
    private var cur: Scene
    private var prev: Scene
    private var blend = 1f
    private var since = 0f
    private val hues = FloatArray(12)

    init {
        cur = makeScene(); prev = cur
        for (s in 0 until 12) hues[s] = rnd.nextFloat() * 360f
        for (k in 0 until 6) for (s in 0 until 12) z[k][s] = -(depth * k + s * depth / subsets) - 0.5f
    }

    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    /** Escenas inspiradas en los presets clásicos, o del todo al azar. */
    private fun makeScene(): Scene {
        val classic = OrbitGen.Shape(-25f, 25f, 0.25f, 1.7f, 5f, 16f, 1f, 9f, 1f, 10f, 0.02f, 0.02f)
        val hueA = baseHue ?: r(0f, 360f)
        val hueB = (hueA + r(40f, 200f)) % 360f
        return when (rnd.nextInt(7)) {
            0 -> Scene(classic, 1.0f, 70f, 0.9f, 0f, 0, 1.2f, 4, 0.4f, hueA, hueB, 0f, 0.35f, 1.0f, 0, 1.2f, "Espacio")
            1 -> Scene(classic.copyTunnel(0.03f, 0.14f), 1.1f, 70f, 1.5f, 0.35f, 0, 1.0f, 0, 0f, hueA, hueB, 0f, 0.25f, 0.6f, 0, 1.0f, "Satisfacción")
            2 -> Scene(classic, 1.0f, 70f, 3.2f, -0.25f, rnd.nextInt(6), 1.0f, 0, 2.5f, hueA, hueB, listOf(4f, 6f, 8f).random(rnd), 0.55f, 0.7f, rnd.nextInt(3), 0.8f, "Trip")
            3 -> Scene(OrbitGen.Shape(-25f, 25f, 0.25f, 1.7f, 5f, 16f, -9f, 1f, 1f, 10f, 0.01f, 0.01f), 1.6f, 120f, 1.6f, 0f, 2, 0.8f, 4, 0.5f, hueA, hueB, 0f, 0.45f, 0.9f, 0, 1.5f, "Hiperespacio")
            4 -> Scene(classic, 1.0f, 70f, 0.6f, 0f, 0, 1.3f, 0, 0f, hueA, hueB, 0f, 0.3f, 1.1f, 0, 2.0f, "Ácido")
            else -> Scene(
                OrbitGen.Shape(r(-30f, -10f), r(10f, 30f), r(0.1f, 0.5f), r(1f, 2.2f), r(3f, 7f), r(10f, 20f),
                    if (rnd.nextBoolean()) r(-9f, 0f) else r(0f, 3f), r(4f, 10f), r(0f, 3f), r(6f, 12f), r(0f, 0.05f), r(0.02f, 0.2f)),
                r(0.8f, 1.6f), r(60f, 125f), r(0.6f, 3f), r(-0.5f, 0.5f), rnd.nextInt(6), r(0.7f, 1.5f), rnd.nextInt(5), r(0f, 2.5f),
                hueA, hueB, listOf(0f, 0f, 0f, 4f, 6f, 8f, 10f, 12f, 16f).random(rnd), r(0f, 0.7f), r(0.3f, 1.3f),
                if (rnd.nextFloat() < 0.75f) 0 else 1 + rnd.nextInt(3), r(0.8f, 2f), "Libre")
        }
    }

    private fun OrbitGen.Shape.copyTunnel(i: Float, o: Float) = OrbitGen.Shape(aMin, aMax, bMin, bMax, cMin, cMax, dMin, dMax, eMin, eMax, i, o)

    private fun request(k: Int) {
        if (requested[k]) return
        requested[k] = true
        val sh = cur.shape
        val seed = rnd.nextLong()
        OrbitGen.exec.execute {
            val data = OrbitGen.level(sh, subsets, points, Random(seed))
            pending += k to data
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quad = alloc(8).apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        progPts = program(VS_PTS, FS_PTS)
        progTrail = program(VS, FS_TRAIL)
        progBright = program(VS, FS_BRIGHT)
        progBlur = program(VS, FS_BLUR)
        progShow = program(VS, FS_SHOW)
        GLES20.glGenBuffers(levels, vbo, 0)
        for (k in 0 until levels) { ready[k] = false; requested[k] = false; request(k) }
    }

    private fun makeTex(i: Int, tw: Int, th: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tw, th, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        fw = max(64, (w * scale).toInt()); fh = max(64, (h * scale).toInt())
        bw = max(16, fw / 4); bh = max(16, fh / 4)
        GLES20.glDeleteTextures(5, tex, 0); GLES20.glDeleteFramebuffers(5, fbo, 0)
        GLES20.glGenTextures(5, tex, 0); GLES20.glGenFramebuffers(5, fbo, 0)
        makeTex(0, fw, fh); makeTex(1, fw, fh); makeTex(2, fw, fh); makeTex(3, bw, bh); makeTex(4, bw, bh)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.06f); last = now
        val time = (now - t0) / 1000f

        // capas nuevas listas: subirlas a la tarjeta gráfica
        while (true) {
            val (k, data) = pending.poll() ?: break
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[k])
            val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).position(0)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb, GLES20.GL_DYNAMIC_DRAW)
            ready[k] = true; requested[k] = false
        }

        // ---------- sonido
        val playing = AudioLevels.level > 0.01f
        level += ((if (playing) AudioLevels.level else 0.18f + 0.05f * sin(time)) - level) * 0.25f
        bass += ((if (playing) AudioLevels.bass else 0.12f + 0.06f * sin(time * 1.7f)) - bass) * 0.35f
        bassAvg += (bass - bassAvg) * 0.02f
        val spec = AudioData.spec
        var lowNow = 0f; for (i in 0 until 6) lowNow += spec[i]; lowNow /= 6f
        lowAvg += (lowNow - lowAvg) * 0.05f
        val kick = playing && lowNow > lowAvg * 1.3f && lowNow - lowPrev > 0.05f
        lowPrev = lowNow
        if ((kick || (bass > bassAvg * 1.45f && bass > 0.12f)) && beat < 0.3f) {
            beat = 1f
            speedKick = if (djStyle) 2.2f else 1.2f
            rotKick = (if (rnd.nextBoolean()) 1f else -1f) * (if (djStyle) 0.9f else 0.5f)
            hueKick += r(15f, 45f)
        }
        beat = max(0f, beat - dt * 2.5f)
        speedKick *= 0.9f; rotKick *= 0.92f
        if (playing && bass > bassAvg * 2.2f && bassAvg < 0.3f && since > 4f) { flash = 1f; if (djStyle) force = true }
        if (AudioLevels.djEvent != lastDjEvent) { lastDjEvent = AudioLevels.djEvent; flash = 1f; force = true }

        // ---------- escenas al azar
        since += dt
        val every = if (djStyle) 12f else 24f
        if (blend >= 1f && (force || (since > every && beat > 0.9f) || since > every + 15f)) {
            prev = cur; cur = makeScene(); blend = 0f; since = 0f; force = false
            for (s in 0 until 12) if (cur.colorMode == 3) hues[s] = lerp(cur.hueA, cur.hueB, rnd.nextFloat())
            // las capas lejanas se rehacen ya con la forma nueva
            val order = (0 until levels).sortedBy { k -> z[k].take(subsets).maxOrNull() ?: 0f }
            order.take(levels - 1).forEach { request(it) }
        }
        if (blend < 1f) blend = min(1f, blend + dt / 1.5f)
        val a = prev; val b = cur; val k = blend
        val fov = lerp(a.fov, b.fov, k)
        val focal = 1f / kotlin.math.tan(Math.toRadians(fov / 2.0).toFloat())
        val spread = lerp(a.spread, b.spread, k)
        val discrete = if (k < 0.5f) a else b

        // ---------- vuelo: la música empuja la cámara y hace girar las órbitas
        val boost = (level * 1.6f + bass * 0.8f).coerceIn(0f, 2f)
        val speed = lerp(a.zoom, b.zoom, k) * (0.25f + boost * lerp(a.audioZoom, b.audioZoom, k)) + speedKick
        spin = lerp(a.rot, b.rot, k) * (0.3f + boost) + rotKick
        angle += spin * dt
        if (discrete.colorMode == 0) for (s in 0 until 12) hues[s] = (hues[s] + discrete.fade * 40f * dt) % 360f

        // ---------- 1) órbitas en la escena (luces que se suman)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glViewport(0, 0, fw, fh)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glUseProgram(progPts)
        val aPos = GLES20.glGetAttribLocation(progPts, "pos")
        GLES20.glEnableVertexAttribArray(aPos)
        uf(progPts, "uFocal", focal); uf(progPts, "uAspect", fw.toFloat() / fh); uf(progPts, "uViewH", fh.toFloat())
        uf(progPts, "uSpread", spread); uf(progPts, "uStyle", discrete.style.toFloat())
        uf(progPts, "uSize", 0.008f * lerp(a.size, b.size, k) * (1f + beat * 0.35f))
        val total = levels * depth
        val uZ = GLES20.glGetUniformLocation(progPts, "uZ"); val uRot = GLES20.glGetUniformLocation(progPts, "uRot")
        val uCol = GLES20.glGetUniformLocation(progPts, "uColor")
        val rgb = FloatArray(3)
        val hA = baseHue ?: discrete.hueA
        val hB = if (baseHue != null) (baseHue!! + 50f) % 360f else discrete.hueB
        for (lv in 0 until levels) {
            for (s in 0 until subsets) {
                z[lv][s] += speed * dt
                if (z[lv][s] > -near) {
                    z[lv][s] -= total
                    if (++moved[lv] >= subsets) { moved[lv] = 0; request(lv) }
                }
            }
            if (!ready[lv]) continue
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[lv])
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, 0)
            for (s in 0 until subsets) {
                val zz = z[lv][s]
                val d = -zz
                // la música pinta la profundidad: cerca = bajos, lejos = agudos
                val band = ((d / total) * 56f).toInt().coerceIn(0, 63)
                // los agudos suenan más bajito: se compensan para que el fondo también brille
                val f = if (playing) (spec[band] * (1f + band / 32f)).coerceAtMost(1.2f) else 0f
                val lvlF = (f * 1.25f).coerceIn(0f, 1.2f)
                var hue = when (discrete.colorMode) {
                    1 -> hA
                    2 -> lerp(hA, hB, s / max(1f, subsets - 1f))
                    3 -> hues[s]
                    4 -> lerp(hA, hB, (f * 1.4f).coerceIn(0f, 1f))
                    else -> hues[s] + lvlF * 60f
                } + hueKick
                hue = ((hue % 360f) + 360f) % 360f
                val sat = if (playing) (0.55f + lvlF * 0.6f + lvlF * boost * 0.1f).coerceIn(0f, 1f) else 0.85f
                val light = if (playing) (0.2f + lvlF * 0.5f + boost * 0.08f).coerceIn(0f, 0.72f) else 0.42f
                hsl(hue, sat, light, rgb)
                val fog = (1f - d / total).coerceIn(0f, 1f).let { it * it } * (0.6f + 0.4f * min(1f, d / 0.4f))
                GLES20.glUniform1f(uZ, zz)
                GLES20.glUniform1f(uRot, angle - 0.785398f + s * 0.0f)
                GLES20.glUniform4f(uCol, rgb[0], rgb[1], rgb[2], fog * (1f + flash * 0.6f))
                GLES20.glDrawArrays(GLES20.GL_POINTS, s * points, points)
            }
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisable(GLES20.GL_BLEND)

        // ---------- 2) estela: lo anterior se queda un poco (rastro de luz)
        val dst = if (trailSrc == 1) 2 else 1
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[dst])
        GLES20.glViewport(0, 0, fw, fh)
        GLES20.glUseProgram(progTrail)
        bindTex(progTrail, "prev", 0, tex[trailSrc]); bindTex(progTrail, "scene", 1, tex[0])
        uf(progTrail, "decay", lerp(a.trail, b.trail, k) * (1f - flash * 0.3f))
        uf(progTrail, "zoom", 1f + 0.01f + beat * 0.02f); uf(progTrail, "rot", spin * 0.01f); uf(progTrail, "aspect", fw.toFloat() / fh)
        drawQuad(progTrail)
        if (++frameN % 12 == 0) sampleDominant()
        trailSrc = dst

        // ---------- 3) brillo difuso (bloom) en pequeño
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[3])
        GLES20.glViewport(0, 0, bw, bh)
        GLES20.glUseProgram(progBright); bindTex(progBright, "t", 0, tex[dst]); drawQuad(progBright)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[4])
        GLES20.glUseProgram(progBlur); bindTex(progBlur, "t", 0, tex[3])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progBlur, "dir"), 1f / bw, 0f); drawQuad(progBlur)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[3])
        bindTex(progBlur, "t", 0, tex[4])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progBlur, "dir"), 0f, 1f / bh); drawQuad(progBlur)

        // ---------- 4) a la pantalla: espejos de caleidoscopio, filtro de color, latido
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progShow)
        bindTex(progShow, "t", 0, tex[dst]); bindTex(progShow, "bloom", 1, tex[3])
        uf(progShow, "segs", discrete.segs); uf(progShow, "time", time); uf(progShow, "aspect", w.toFloat() / h)
        uf(progShow, "glow", 1f + beat * (if (djStyle) 0.35f else 0.15f) + flash * 0.4f)
        uf(progShow, "bloomAmt", lerp(a.bloom, b.bloom, k) * (1f + beat * 0.6f + flash))
        uf(progShow, "pulse", beat * (if (djStyle) 1f else 0.5f))
        uf(progShow, "filt", discrete.filter.toFloat())
        drawQuad(progShow)
        flash = max(0f, flash - dt * 1.6f)
    }

    private var lastDjEvent = AudioLevels.djEvent

    private fun bindTex(p: Int, name: String, unit: Int, t: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, name), unit)
    }

    /** Lee 4 parches pequeños del cuadro y saca el color más vivo que predomina. */
    private fun sampleDominant() {
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var sw = 0.0
        val spots = arrayOf(floatArrayOf(0.3f, 0.3f), floatArrayOf(0.7f, 0.3f), floatArrayOf(0.3f, 0.7f), floatArrayOf(0.7f, 0.7f))
        for (sp in spots) {
            val x = (fw * sp[0]).toInt().coerceIn(0, fw - 24); val y = (fh * sp[1]).toInt().coerceIn(0, fh - 24)
            pix.clear()
            GLES20.glReadPixels(x, y, 24, 24, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pix)
            for (i in 0 until 24 * 24) {
                val rr = pix.get(i * 4).toInt() and 0xFF; val gg = pix.get(i * 4 + 1).toInt() and 0xFF; val bb = pix.get(i * 4 + 2).toInt() and 0xFF
                val mx = maxOf(rr, gg, bb); val mn = minOf(rr, gg, bb)
                val wgt = ((mx - mn) / 255.0) * (mx / 255.0) + 0.002
                sr += rr * wgt; sg += gg * wgt; sb += bb * wgt; sw += wgt
            }
        }
        if (sw <= 0.01) return
        val hsv = FloatArray(3)
        Color.colorToHSV(Color.rgb((sr / sw).toInt(), (sg / sw).toInt(), (sb / sw).toInt()), hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.55f); hsv[2] = 1f
        dominant = Color.HSVToColor(hsv)
    }

    private fun hsl(hDeg: Float, s: Float, l: Float, out: FloatArray) {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = (hDeg / 60f) % 6f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f); hp < 2f -> Triple(x, c, 0f); hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c); hp < 5f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        out[0] = r1 + m; out[1] = g1 + m; out[2] = b1 + m
    }

    private fun drawQuad(p: Int) {
        val pos = GLES20.glGetAttribLocation(p, "pos")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uf(p: Int, n: String, v: Float) = GLES20.glUniform1f(GLES20.glGetUniformLocation(p, n), v)

    companion object {
        fun alloc(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        fun program(vs: String, fs: String): Int {
            fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }

        const val VS = """
            attribute vec2 pos; varying vec2 v;
            void main(){ v = pos * 0.5 + 0.5; gl_Position = vec4(pos, 0.0, 1.0); }"""

        // órbitas: perspectiva hacia el fondo del túnel y tamaño de cada luz según la distancia
        const val VS_PTS = """
            attribute vec2 pos;
            uniform float uZ, uRot, uSpread, uFocal, uAspect, uSize, uViewH;
            void main(){
                float c = cos(uRot), s = sin(uRot);
                vec2 p = vec2(pos.x * c - pos.y * s, pos.x * s + pos.y * c) * uSpread;
                float d = max(-uZ, 0.02);
                gl_Position = vec4(p.x * uFocal / d / uAspect, p.y * uFocal / d, 0.0, 1.0);
                gl_PointSize = clamp(uSize * uViewH * uFocal / d, 1.0, 40.0);
            }"""

        // 6 formas de luz: galaxia, anillo, estrella, cubito, corazón, chispa
        const val FS_PTS = """
            precision mediump float;
            uniform vec4 uColor; uniform float uStyle;
            void main(){
                vec2 d = gl_PointCoord * 2.0 - 1.0; float r = length(d); float a;
                if (uStyle < 0.5) a = exp(-r * r * 4.0);
                else if (uStyle < 1.5) a = smoothstep(0.45, 0.7, r) * smoothstep(1.0, 0.75, r) * 1.6;
                else if (uStyle < 2.5) { vec2 q = abs(d); a = max(exp(-q.x * 10.0) * exp(-q.y * 1.6), exp(-q.y * 10.0) * exp(-q.x * 1.6)) + exp(-r * r * 9.0) * 0.6; }
                else if (uStyle < 3.5) { vec2 q = abs(d); float m = max(q.x, q.y); a = step(m, 0.72) * (0.55 + 0.45 * (1.0 - m)); }
                else if (uStyle < 4.5) { vec2 q = vec2(d.x, -d.y) * 1.3 + vec2(0.0, 0.15); float x2 = q.x * q.x; float k = x2 + q.y * q.y - 1.0; a = step(k * k * k - x2 * q.y * q.y * q.y, 0.0) * 0.95; }
                else { vec2 q = abs(d); a = exp(-q.x * 16.0) * exp(-q.y * 0.9) + exp(-q.y * 16.0) * exp(-q.x * 0.9) * 0.7; }
                if (a < 0.01) discard;
                gl_FragColor = vec4(uColor.rgb * a * uColor.a, 1.0);
            }"""

        const val FS_TRAIL = """
            precision mediump float;
            varying vec2 v; uniform sampler2D prev; uniform sampler2D scene;
            uniform float decay, zoom, rot, aspect;
            void main(){
                vec2 p = v - 0.5; p.x *= aspect;
                float c = cos(rot), s = sin(rot);
                p = vec2(p.x * c - p.y * s, p.x * s + p.y * c) / zoom;
                p.x /= aspect; p += 0.5;
                vec3 col = texture2D(prev, p).rgb * decay + texture2D(scene, v).rgb;
                gl_FragColor = vec4(min(col, vec3(1.0)), 1.0);
            }"""

        const val FS_BRIGHT = """
            precision mediump float;
            varying vec2 v; uniform sampler2D t;
            void main(){ vec3 c = texture2D(t, v).rgb; gl_FragColor = vec4(max(c - 0.25, 0.0) * 1.5, 1.0); }"""

        const val FS_BLUR = """
            precision mediump float;
            varying vec2 v; uniform sampler2D t; uniform vec2 dir;
            void main(){
                vec3 c = texture2D(t, v).rgb * 0.227;
                c += (texture2D(t, v + dir * 1.38).rgb + texture2D(t, v - dir * 1.38).rgb) * 0.316;
                c += (texture2D(t, v + dir * 3.23).rgb + texture2D(t, v - dir * 3.23).rgb) * 0.070;
                gl_FragColor = vec4(c, 1.0);
            }"""

        const val FS_SHOW = """
            precision mediump float;
            varying vec2 v; uniform sampler2D t; uniform sampler2D bloom;
            uniform float segs, time, aspect, glow, bloomAmt, pulse, filt;
            void main(){
                vec2 p = v - 0.5; p.x *= aspect;
                p *= (1.0 - pulse * 0.07);
                if (segs > 0.5) {
                    float a = atan(p.y, p.x) + time * 0.04 + pulse * 0.1; float r = length(p);
                    float s = 6.2831853 / segs; a = mod(a, s); a = abs(a - s * 0.5);
                    p = vec2(cos(a), sin(a)) * r;
                }
                p.x /= aspect; vec2 uv = p + 0.5;
                vec3 c = texture2D(t, uv).rgb * glow + texture2D(bloom, uv).rgb * bloomAmt;
                if (filt > 0.5 && filt < 1.5) c = c.gbr;
                else if (filt > 1.5 && filt < 2.5) c = floor(c * 5.0) / 5.0;
                else if (filt > 2.5) { float l = dot(c, vec3(0.3, 0.59, 0.11)); c = mix(vec3(0.1, 0.0, 0.25), vec3(1.0, 0.45, 0.9), l) * min(1.0, l * 3.0); }
                float vig = smoothstep(1.15, 0.3, length(v - 0.5) * 1.4);
                gl_FragColor = vec4(c * vig, 1.0);
            }"""
    }
}
