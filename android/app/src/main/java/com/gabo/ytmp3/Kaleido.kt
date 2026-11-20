package com.gabo.ytmp3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Nivel de sonido de lo que suena (lo escribe el reproductor, lo leen los caleidoscopios). */
object AudioLevels {
    @Volatile var level = 0f   // 0..1 volumen general
    @Volatile var bass = 0f    // 0..1 graves
    /** Sube en cada mezcla del DJ (el visualizador cambia de efecto con ella). */
    @Volatile var djEvent = 0
}

/**
 * Caleidoscopio al estilo del visualizador de Windows Media: figuras de colores que se
 * reflejan en espejo, cambian de forma y color solas y laten con la música.
 */
class KaleidoRenderer(seed: Long) {
    private val rnd = java.util.Random(seed)
    private val n = intArrayOf(6, 8, 10, 12)[rnd.nextInt(4)]
    private val baseHue = rnd.nextFloat() * 360f
    private val shapes = 6
    private val sp = FloatArray(shapes) { 0.3f + rnd.nextFloat() * 0.9f }
    private val ph = FloatArray(shapes) { rnd.nextFloat() * 6.28f }
    private val sz = FloatArray(shapes) { 0.06f + rnd.nextFloat() * 0.14f }
    private val kind = IntArray(shapes) { rnd.nextInt(3) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val bg = Paint()
    private val wedge = Path()
    private val petal = Path()
    private val hsv = FloatArray(3)
    private var lastW = -1

    fun draw(c: Canvas, w: Int, h: Int, t: Float, level: Float, bass: Float) {
        val cx = w / 2f; val cy = h / 2f
        val r = maxOf(w, h) * 0.75f
        val hueNow = (baseHue + t * 12f + bass * 80f) % 360f
        if (w != lastW) { lastW = w }
        bg.shader = RadialGradient(cx, cy, r, intArrayOf(hsvColor(hueNow + 180, 0.7f, 0.22f), Color.BLACK),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val step = 360f / n
        wedge.reset()
        wedge.moveTo(0f, 0f)
        val a = Math.toRadians(step.toDouble() + 0.6).toFloat()
        wedge.lineTo(r * 1.2f, 0f)
        wedge.lineTo(r * 1.2f * cos(a), r * 1.2f * sin(a))
        wedge.close()
        val pulse = 1f + level * 1.6f
        for (i in 0 until n) {
            c.save()
            c.translate(cx, cy)
            c.rotate(i * step + t * 6f)
            if (i % 2 == 1) { c.rotate(step); c.scale(1f, -1f) }
            c.clipPath(wedge)
            for (k in 0 until shapes) {
                val tt = t * sp[k] + ph[k]
                val dist = r * (0.12f + 0.55f * (0.5f + 0.5f * sin(tt * 0.7f)))
                val ang = Math.toRadians((step * (0.5f + 0.45f * sin(tt * 1.3f))).toDouble())
                val x = (dist * cos(ang)).toFloat(); val y = (dist * sin(ang)).toFloat()
                val s = r * sz[k] * pulse * (0.8f + 0.4f * sin(tt * 2f))
                paint.color = hsvColor(hueNow + k * 47f + sin(tt) * 30f, 0.85f, 0.9f)
                paint.alpha = (90 + 100 * level).toInt().coerceAtMost(220)
                when (kind[k]) {
                    0 -> c.drawCircle(x, y, s, paint)
                    1 -> c.drawOval(x - s * 1.8f, y - s * 0.6f, x + s * 1.8f, y + s * 0.6f, paint)
                    else -> {
                        petal.reset()
                        petal.moveTo(x - s * 1.5f, y)
                        petal.quadTo(x, y - s * 1.6f, x + s * 1.5f, y)
                        petal.quadTo(x, y + s * 1.6f, x - s * 1.5f, y)
                        c.drawPath(petal, paint)
                    }
                }
            }
            c.restore()
        }
        // Brillo central que late con los graves
        paint.color = hsvColor(hueNow + 40, 0.3f, 1f)
        paint.alpha = (60 + 160 * bass).toInt().coerceAtMost(230)
        c.drawCircle(cx, cy, r * (0.05f + 0.12f * bass), paint)
    }

    private fun hsvColor(h: Float, s: Float, v: Float): Int {
        hsv[0] = ((h % 360f) + 360f) % 360f; hsv[1] = s; hsv[2] = v
        return Color.HSVToColor(hsv)
    }

    companion object {
        fun bitmap(seed: Long, size: Int = 512): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            KaleidoRenderer(seed).draw(Canvas(b), size, size, (seed % 97).toFloat(), 0.35f, 0.3f)
            return b
        }

        /** Portada caleidoscopio guardada en caché (para listas, notificación y pantalla de bloqueo). */
        fun artFile(ctx: Context, trackId: Long): File {
            val f = File(File(ctx.cacheDir, "kaleido").apply { mkdirs() }, "t$trackId.jpg")
            if (!f.exists()) runCatching {
                val b = bitmap(trackId * 7919 + 13, 384)
                f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                b.recycle()
            }
            return f
        }

        /** Avatar redondo a partir del apodo. */
        fun avatar(nick: String, size: Int = 256): Bitmap {
            val src = bitmap(nick.hashCode().toLong() * 31 + 7, size)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p)
            return out
        }
    }
}

/** Vista animada del caleidoscopio. Solo se anima mientras [running] y está en pantalla. */
class KaleidoView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var renderer = KaleidoRenderer(1)
    private val t0 = SystemClock.uptimeMillis()
    private var lvl = 0f; private var bass = 0f
    private var lastFrame = 0L

    var seed: Long = 1
        set(v) { if (field != v) { field = v; renderer = KaleidoRenderer(v * 7919 + 13); invalidate() } }

    var running = false
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f + (seed % 97)
        lvl += (AudioLevels.level - lvl) * 0.35f
        bass += (AudioLevels.bass - bass) * 0.35f
        val l = if (running) lvl else 0.25f
        val b = if (running) bass else 0.2f
        renderer.draw(c, width, height, if (running) t else (seed % 97).toFloat(), l, b)
        if (running && isShown) {
            val now = SystemClock.uptimeMillis()
            val wait = (33 - (now - lastFrame)).coerceIn(0, 33)
            lastFrame = now + wait
            postInvalidateDelayed(wait)
        }
    }
}

object Covers {
    /** Archivo de portada a mostrar: la real o el caleidoscopio (según ajustes). */
    fun fileFor(ctx: Context, t: Track): File {
        val p = Prefs2(ctx)
        val real = t.coverPath?.let(::File)?.takeIf { it.exists() }
        return if (real != null && !p.kaleidoAll) real else Kaleido.artFile(ctx, t.id)
    }

    fun usesKaleido(ctx: Context, t: Track): Boolean {
        val p = Prefs2(ctx)
        return p.kaleidoAll || t.coverPath?.let { File(it).exists() } != true
    }

    fun avatarBitmap(ctx: Context): Bitmap {
        val p = Prefs2(ctx)
        p.photoPath?.let { BitmapFactory.decodeFile(it) }?.let { src ->
            val size = minOf(src.width, src.height)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).apply {
                translate(-(src.width - size) / 2f, -(src.height - size) / 2f)
                drawCircle(src.width / 2f, src.height / 2f, size / 2f, paint)
            }
            return out
        }
        return KaleidoRenderer.avatar(p.nickname.ifEmpty { "DownSong" })
    }
}

private object Kaleido {
    fun artFile(ctx: Context, id: Long) = KaleidoRenderer.artFile(ctx, id)
}

@Suppress("unused") private const val TAU = (2 * PI).toFloat()
