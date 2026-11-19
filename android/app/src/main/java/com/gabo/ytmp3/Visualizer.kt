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
 * Visualizador hipnótico (OpenGL): estela que se retroalimenta (zoom + giro + desvanecido),
 * espejos de caleidoscopio, onda y espectro reales, ráfagas con el bajo y 12 efectos que se
 * transforman solos. Inspirado en los visualizadores clásicos, con diseño y código propios.
 */
class VizView(ctx: Context) : GLSurfaceView(ctx) {
    private val renderer = VizRenderer()
    /** Color base por estado de ánimo (null = arcoíris que va cambiando). */
    var hue: Float? = null
        set(v) { field = v; renderer.baseHue = v }
    /** Silueta de la canción (Modo DJ) y avance 0..1. */
    var envelope: ByteArray = ByteArray(0)
        set(v) { field = v; renderer.envelope = v }
    var progress: Float = 0f
        set(v) { field = v; renderer.progress = v }
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
        val mode = Prefs2(ctx).usage
        fps = if (mode == UsageMode.MAX) 60 else 30
        renderer.scale = if (mode == UsageMode.MAX) 0.75f else 0.5f
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

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}

private class VizRenderer : GLSurfaceView.Renderer {
    @Volatile var baseHue: Float? = null
    @Volatile var envelope: ByteArray = ByteArray(0)
    @Volatile var progress = 0f
    @Volatile var djStyle = false
    @Volatile var scale = 0.5f

    private var w = 1; private var h = 1; private var fw = 1; private var fh = 1
    private val fbo = IntArray(2); private val tex = IntArray(2); private var src = 0
    private var progFeed = 0; private var progShape = 0; private var progShow = 0
    private lateinit var quad: FloatBuffer
    private var verts: FloatBuffer = alloc(8192 * 6)
    private val t0 = SystemClock.uptimeMillis()
    private var bassAvg = 0.1f; private var bass = 0f; private var level = 0f
    private var beat = 0f
    private val rnd = Random(7)

    // 12 efectos: zoom, giro, deformación, decaimiento, emisor, espejos, velocidad de color
    private data class Preset(val zoom: Float, val rot: Float, val warpMode: Int, val warp: Float, val decay: Float,
                              val emitter: Int, val segs: Float, val hueSpeed: Float)
    private val presets = listOf(
        Preset(1.025f, 0.004f, 1, 0.6f, 0.955f, 1, 8f, 18f),    // flor que gira
        Preset(1.04f, -0.01f, 0, 0f, 0.94f, 2, 0f, 25f),        // túnel de espectro
        Preset(0.985f, 0.006f, 2, 1f, 0.96f, 0, 6f, 12f),       // olas espejo
        Preset(1.02f, 0.02f, 1, 1.2f, 0.95f, 5, 12f, 30f),      // espiral lissajous
        Preset(1.03f, 0f, 0, 0f, 0.93f, 4, 0f, 40f),            // estrellas que explotan
        Preset(1.01f, -0.006f, 2, 0.6f, 0.965f, 3, 4f, 15f),    // barras de fuego en cruz
        Preset(1.05f, 0.012f, 1, 0.3f, 0.92f, 1, 0f, 22f),      // anillos hacia el infinito
        Preset(0.98f, -0.015f, 1, 0.9f, 0.96f, 2, 10f, 35f),    // mandala que respira
        Preset(1.015f, 0.003f, 2, 1.4f, 0.97f, 0, 0f, 10f),     // aurora líquida
        Preset(1.035f, 0.025f, 1, 0.8f, 0.945f, 4, 6f, 50f),    // galaxia caleidoscopio
        Preset(1.0f, 0.008f, 2, 0.8f, 0.955f, 5, 8f, 20f),      // nudos de luz
        Preset(1.02f, -0.004f, 0, 0f, 0.95f, 6, 0f, 14f),       // silueta de la canción (DJ)
    )
    private var cur = 0; private var nxt = 0; private var blend = 1f
    private val djSeq = intArrayOf(11, 0, 11, 3, 11, 9, 11, 7, 11, 2)
    private var djIdx = 0
    private var presetSince = 0f

    fun nextPreset() { presetSince = 999f }

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var hue: Float)
    private val parts = ArrayList<Particle>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quad = alloc(8).apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        progFeed = program(VS, FS_FEED)
        progShape = program(VS_SHAPE, FS_SHAPE)
        progShow = program(VS, FS_SHOW)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        fw = max(64, (w * scale).toInt()); fh = max(64, (h * scale).toInt())
        GLES20.glDeleteTextures(2, tex, 0); GLES20.glDeleteFramebuffers(2, fbo, 0)
        GLES20.glGenTextures(2, tex, 0); GLES20.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw, fh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (djStyle) { cur = 11; nxt = 11 } else { cur = rnd.nextInt(11); nxt = cur }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDrawFrame(gl: GL10?) {
        val time = (SystemClock.uptimeMillis() - t0) / 1000f
        val dt = 1f / 40f
        // sonido (si no suena nada, un vaivén suave para que siga vivo)
        val playing = AudioLevels.level > 0.01f
        level += ((if (playing) AudioLevels.level else 0.15f + 0.05f * sin(time)) - level) * 0.3f
        bass += ((if (playing) AudioLevels.bass else 0.12f + 0.08f * sin(time * 2.1f)) - bass) * 0.35f
        bassAvg += (bass - bassAvg) * 0.02f
        if (bass > bassAvg * 1.45f && bass > 0.12f && beat < 0.2f) { beat = 1f; onBeat(time) }
        beat = max(0f, beat - dt * 2.5f)

        // cambio automático de efecto con fundido
        presetSince += dt
        if (blend >= 1f && (presetSince > 20f || (presetSince > 12f && beat > 0.9f))) {
            nxt = if (djStyle) { djIdx = (djIdx + 1) % djSeq.size; djSeq[djIdx] }
                  else { var n: Int; do { n = rnd.nextInt(11) } while (n == cur); n }
            blend = 0f; presetSince = 0f
        }
        if (blend < 1f) { blend = min(1f, blend + dt / 2.5f); if (blend >= 1f) cur = nxt }
        val a = presets[cur]; val b = presets[nxt]; val k = if (cur == nxt) 1f else blend
        val zoom = lerp(a.zoom, b.zoom, k) + beat * 0.03f
        val rot = lerp(a.rot, b.rot, k) * (1f + level)
        val decay = lerp(a.decay, b.decay, k)
        val segs = if (k < 0.5f) a.segs else b.segs
        val hueSpeed = lerp(a.hueSpeed, b.hueSpeed, k)
        val hue = (baseHue?.let { it + 30f * sin(time * 0.3f) } ?: (time * hueSpeed)) + bass * 60f

        val dst = 1 - src
        // 1) estela: dibujar el cuadro anterior ampliado, girado y desvanecido
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[dst])
        GLES20.glViewport(0, 0, fw, fh)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(progFeed)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[src])
        uf(progFeed, "zoom", zoom); uf(progFeed, "rot", rot); uf(progFeed, "time", time)
        uf(progFeed, "decay", decay); uf(progFeed, "bass", bass); uf(progFeed, "aspect", fw.toFloat() / fh)
        uf(progFeed, "warp", lerp(a.warp, b.warp, k) * (0.6f + level)); uf(progFeed, "mode", (if (k < 0.5f) a.warpMode else b.warpMode).toFloat())
        drawQuad(progFeed)

        // 2) emisores (suma de luz)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        val ea = if (cur == nxt) 1f else 1f - blend
        emit(a.emitter, hue, time, ea)
        if (cur != nxt) emit(b.emitter, hue + 90f, time, blend)
        drawParticles(dt)

        // 3) mostrar con espejos de caleidoscopio
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(progShow)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[dst])
        uf(progShow, "segs", segs); uf(progShow, "time", time); uf(progShow, "aspect", w.toFloat() / h)
        uf(progShow, "glow", 0.95f + beat * 0.2f)
        drawQuad(progShow)
        src = dst
    }

    private fun onBeat(time: Float) {
        val n = 26
        val hue = (baseHue ?: (time * 40f)) + rnd.nextFloat() * 60f
        repeat(n) {
            val ang = rnd.nextFloat() * 2 * PI.toFloat()
            val sp = 0.4f + rnd.nextFloat() * 0.9f
            parts += Particle(0f, 0f, cos(ang) * sp, sin(ang) * sp, 1f, hue + it * 4f)
        }
        while (parts.size > 400) parts.removeAt(0)
    }

    private fun emit(kind: Int, hue: Float, time: Float, alpha: Float) {
        verts.clear()
        var count = 0
        val wave = AudioData.wave; val spec = AudioData.spec
        val asp = fh.toFloat() / fw
        fun v(x: Float, y: Float, c: Int, al: Float) {
            verts.put(x).put(y).put(Color.red(c) / 255f).put(Color.green(c) / 255f).put(Color.blue(c) / 255f).put(al * alpha * 0.6f); count++
        }
        val col = hsv(hue, 0.85f, 1f); val col2 = hsv(hue + 120f, 0.8f, 1f)
        when (kind) {
            0 -> for (i in 0 until 256) {               // onda horizontal
                val x = -0.9f + 1.8f * i / 255f
                v(x, wave[i] * 0.6f * (1f + level), if (i % 2 == 0) col else col2, 0.9f)
            }
            1, 7 -> for (i in 0..256) {                  // anillo de onda
                val ang = 2 * PI.toFloat() * i / 256f
                val r = 0.28f + wave[i % 256] * 0.25f + bass * 0.12f
                v(cos(ang) * r * asp, sin(ang) * r, if (i % 3 == 0) col2 else col, 0.85f)
            }
            2 -> for (i in 0 until 64) {                 // rayos de espectro
                val ang = 2 * PI.toFloat() * i / 64f + time * 0.2f
                val r0 = 0.12f; val r1 = r0 + spec[i] * 0.55f
                val c = hsv(hue + i * 3f, 0.9f, 1f)
                v(cos(ang) * r0 * asp, sin(ang) * r0, c, 0.9f); v(cos(ang) * r1 * asp, sin(ang) * r1, c, 0.9f)
            }
            3 -> for (i in 0 until 64) {                 // barras de fuego
                val x = -0.95f + 1.9f * i / 63f
                val hh = spec[i] * 0.9f
                val c = hsv(hue + i * 2f, 0.9f, 1f)
                v(x, -0.6f, c, 0.9f); v(x, -0.6f + hh, c, 0.9f)
                v(x, 0.6f, c, 0.6f); v(x, 0.6f - hh, c, 0.6f)
            }
            4 -> {}                                      // solo estrellas (partículas)
            5 -> for (i in 0 until 255) {                // figura de lissajous
                v(wave[i] * 0.8f * asp + sin(time + i * 0.05f) * 0.05f, wave[(i + 64) % 256] * 0.8f, if (i % 2 == 0) col else col2, 0.8f)
            }
            6 -> {                                       // silueta de la canción con el avance (DJ)
                val env = envelope
                if (env.isNotEmpty()) {
                    val center = (progress * env.size).toInt()
                    val span = 120
                    for (j in -span until span) {
                        val idx = center + j
                        if (idx !in env.indices) continue
                        val x = j / span.toFloat()
                        val amp = (env[idx].toInt() and 0xFF) / 255f * 0.55f
                        val c = if (j < 0) hsv(hue, 0.7f, 0.8f) else hsv(hue + 140f, 0.9f, 1f)
                        v(x, -amp, c, 0.35f); v(x, amp, c, 0.35f)
                    }
                    v(0f, -0.7f, Color.WHITE, 0.9f); v(0f, 0.7f, Color.WHITE, 0.9f)
                } else for (i in 0 until 256) v(-0.9f + 1.8f * i / 255f, wave[i] * 0.5f, col, 0.9f)
            }
        }
        if (count == 0) return
        verts.position(0)
        GLES20.glUseProgram(progShape)
        val pos = GLES20.glGetAttribLocation(progShape, "pos"); val c = GLES20.glGetAttribLocation(progShape, "col")
        verts.position(0); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 24, verts); GLES20.glEnableVertexAttribArray(pos)
        verts.position(2); GLES20.glVertexAttribPointer(c, 4, GLES20.GL_FLOAT, false, 24, verts); GLES20.glEnableVertexAttribArray(c)
        uf(progShape, "psize", 3f)
        val mode = when (kind) { 2, 3, 6 -> GLES20.GL_LINES; else -> GLES20.GL_LINE_STRIP }
        GLES20.glLineWidth(if (kind == 6) 3f else 2.5f)
        GLES20.glDrawArrays(mode, 0, count)

    }

    private fun drawParticles(dt: Float) {
        if (parts.isEmpty()) return
        verts.clear()
        val asp = fh.toFloat() / fw
        val it = parts.iterator()
        var n = 0
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx * dt; p.y += p.vy * dt; p.vx *= 0.985f; p.vy *= 0.985f; p.life -= dt * 0.6f
            if (p.life <= 0f || hypot(p.x, p.y) > 1.5f) { it.remove(); continue }
            val c = hsv(p.hue, 0.6f, 1f)
            verts.put(p.x * asp).put(p.y).put(Color.red(c) / 255f).put(Color.green(c) / 255f).put(Color.blue(c) / 255f).put(p.life); n++
        }
        if (n == 0) return
        GLES20.glUseProgram(progShape)
        val pos = GLES20.glGetAttribLocation(progShape, "pos"); val c = GLES20.glGetAttribLocation(progShape, "col")
        verts.position(0); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 24, verts); GLES20.glEnableVertexAttribArray(pos)
        verts.position(2); GLES20.glVertexAttribPointer(c, 4, GLES20.GL_FLOAT, false, 24, verts); GLES20.glEnableVertexAttribArray(c)
        uf(progShape, "psize", 7f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
    }

    private fun drawQuad(p: Int) {
        val pos = GLES20.glGetAttribLocation(p, "pos")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "t"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uf(p: Int, n: String, v: Float) = GLES20.glUniform1f(GLES20.glGetUniformLocation(p, n), v)

    private fun hsv(h: Float, s: Float, v: Float): Int = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))

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
        const val FS_FEED = """
            precision mediump float;
            varying vec2 v; uniform sampler2D t;
            uniform float zoom, rot, time, decay, bass, warp, mode, aspect;
            vec3 hue(vec3 c, float a){ vec3 k = vec3(0.57735); float ca = cos(a);
                return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca); }
            void main(){
                vec2 p = v - 0.5; p.x *= aspect;
                float r = length(p); float a = atan(p.y, p.x);
                if (mode > 0.5 && mode < 1.5) a += warp * 0.06 * (0.6 - r);
                if (mode > 1.5) r += warp * 0.004 * sin(r * 30.0 - time * 3.0);
                a += rot; r /= zoom;
                vec2 q = vec2(cos(a), sin(a)) * r; q.x /= aspect; q += 0.5;
                vec3 c = max(texture2D(t, q).rgb * decay - vec3(0.006), vec3(0.0));
                c = hue(c, 0.015 + bass * 0.04);
                gl_FragColor = vec4(c, 1.0);
            }"""
        const val VS_SHAPE = """
            attribute vec2 pos; attribute vec4 col; varying vec4 vc; uniform float psize;
            void main(){ vc = col; gl_PointSize = psize; gl_Position = vec4(pos, 0.0, 1.0); }"""
        const val FS_SHAPE = """
            precision mediump float; varying vec4 vc;
            void main(){ gl_FragColor = vc; }"""
        const val FS_SHOW = """
            precision mediump float;
            varying vec2 v; uniform sampler2D t; uniform float segs, time, aspect, glow;
            void main(){
                vec2 p = v - 0.5; p.x *= aspect;
                if (segs > 0.5) {
                    float a = atan(p.y, p.x) + time * 0.05; float r = length(p);
                    float s = 6.2831853 / segs; a = mod(a, s); a = abs(a - s * 0.5);
                    p = vec2(cos(a), sin(a)) * r;
                }
                p.x /= aspect;
                vec3 c = texture2D(t, p + 0.5).rgb * glow;
                float vig = smoothstep(1.1, 0.25, length(v - 0.5) * 1.4);
                gl_FragColor = vec4(c * vig, 1.0);
            }"""
    }
}
