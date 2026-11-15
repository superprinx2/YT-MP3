package com.gabo.ytmp3

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private fun View.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

/** Vibración corta, como el "tap" de iOS. */
fun View.haptic(strong: Boolean = false) {
    val c = if (strong && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.CONTEXT_CLICK
    performHapticFeedback(c)
}

/** Efecto de "hundirse" al tocar y rebotar al soltar. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(scale: Float = 0.95f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (v.isEnabled) v.animate().scaleX(scale).scaleY(scale)
                .setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f)
                .setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
        }
        false
    }
}

/** Pequeño salto para llamar la atención (p. ej. cuando el botón Descargar se habilita). */
fun View.bounce() {
    animate().cancel()
    scaleX = 0.9f; scaleY = 0.9f
    animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(OvershootInterpolator(4f)).start()
}

/**
 * Fondo animado del encabezado: degradado que se mueve lentamente y notas musicales
 * que flotan hacia arriba.
 */
class HeroView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val colors = intArrayOf(
        Color.parseColor("#FF2D55"), Color.parseColor("#FF375F"),
        Color.parseColor("#FF6B3D"), Color.parseColor("#AF52DE"), Color.parseColor("#FF2D55"))
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = ResourcesCompat.getFont(ctx, R.font.inter_bold)
    }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private val clip = Path()
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private val start = SystemClock.uptimeMillis()
    private val glyphs = arrayOf("♪", "♫", "♬", "♩")

    /** Se pausa durante las descargas para dejar toda la CPU a yt-dlp/ffmpeg. */
    var animating = true
        set(v) { if (field != v) { field = v; if (v) invalidate() } }
    private var frozenT = 0f

    private data class Note(var x: Float, var y: Float, val speed: Float, val size: Float, val glyph: String, val phase: Float)
    private val notes = ArrayList<Note>()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset(); clip.addRoundRect(rect, dp(22f), dp(22f), Path.Direction.CW)
        shader = LinearGradient(0f, 0f, w * 2f, h.toFloat(), colors, null, Shader.TileMode.MIRROR)
        bg.shader = shader
        notes.clear()
        val r = Random(7)
        repeat(9) {
            notes += Note(r.nextFloat() * w, r.nextFloat() * h, dp(6f + r.nextFloat() * 10f),
                dp(14f + r.nextFloat() * 16f), glyphs[it % glyphs.size], r.nextFloat() * 6.28f)
        }
    }

    override fun onDraw(c: Canvas) {
        val t = if (animating) ((SystemClock.uptimeMillis() - start) / 1000f).also { frozenT = it } else frozenT
        c.save()
        c.clipPath(clip)
        matrix.setTranslate(-width * (0.5f + 0.5f * sin(t * 0.25f)), 0f)
        shader?.setLocalMatrix(matrix)
        c.drawRect(rect, bg)

        // burbujas suaves
        bubble.alpha = 28
        c.drawCircle(width * 0.85f, height * 0.15f, height * 0.55f, bubble)
        bubble.alpha = 20
        c.drawCircle(width * 0.12f, height * 1.05f, height * 0.45f, bubble)

        // notas flotando
        for (n in notes) {
            val y = ((n.y - t * n.speed) % (height + n.size) + height + n.size) % (height + n.size)
            val x = n.x + sin(t * 0.8f + n.phase) * dp(8f)
            notePaint.textSize = n.size
            notePaint.alpha = (60 + 50 * sin(t + n.phase)).toInt().coerceIn(20, 120)
            c.drawText(n.glyph, x, y, notePaint)
        }
        c.restore()
        if (isShown && animating) postInvalidateDelayed(33)   // ~30 fps es suficiente
    }
}

/** Gráfico de línea de la velocidad total (MB/s) durante la descarga. */
class SparklineView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var values: List<Float> = emptyList()
        set(v) { field = v; invalidate() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(ctx, R.color.ios_blue)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(ctx, R.color.ios_blue) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.ios_separator); strokeWidth = dp(0.5f)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = dp(4f)
        for (i in 1..2) c.drawLine(0f, h * i / 3, w, h * i / 3, grid)
        if (values.size < 2) return
        val maxV = max(values.maxOrNull() ?: 1f, 0.5f) * 1.15f
        val step = (w - pad * 2) / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = pad + i * step
            val y = h - pad - (v / maxV) * (h - pad * 2)
            if (i == 0) path.moveTo(x, y) else {
                val px = pad + (i - 1) * step
                val py = h - pad - (values[i - 1] / maxV) * (h - pad * 2)
                path.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y)
            }
        }
        val area = Path(path).apply { lineTo(pad + (values.size - 1) * step, h); lineTo(pad, h); close() }
        fill.shader = LinearGradient(0f, 0f, 0f, h,
            (line.color and 0x00FFFFFF) or 0x55000000, line.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        c.drawPath(path, line)
        val lx = pad + (values.size - 1) * step
        val ly = h - pad - (values.last() / maxV) * (h - pad * 2)
        c.drawCircle(lx, ly, dp(3.5f), dot)
    }
}

/** Barras de los últimos 7 días. */
class BarChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var data: List<Pair<String, Int>> = emptyList()
        set(v) { field = v; animStart = SystemClock.uptimeMillis(); invalidate() }
    private var animStart = 0L
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(ctx, R.color.ios_fill) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.ios_secondary); textSize = dp(11f); textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(ctx, R.font.inter_medium)
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.ios_label); textSize = dp(11f); textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(ctx, R.font.inter_semibold)
    }
    private val blue = ContextCompat.getColor(ctx, R.color.ios_blue)
    private val purple = Color.parseColor("#AF52DE")

    override fun onDraw(c: Canvas) {
        if (data.isEmpty()) return
        val k = ((SystemClock.uptimeMillis() - animStart) / 600f).coerceIn(0f, 1f)
        val ease = 1 - (1 - k) * (1 - k) * (1 - k)
        val w = width.toFloat(); val h = height.toFloat()
        val bottom = h - dp(18f); val top = dp(16f)
        val slot = w / data.size; val bw = slot * 0.46f
        val maxV = max(1, data.maxOf { it.second })
        data.forEachIndexed { i, (l, v) ->
            val cx = slot * i + slot / 2
            val r = RectF(cx - bw / 2, top, cx + bw / 2, bottom)
            c.drawRoundRect(r, bw / 2, bw / 2, track)
            if (v > 0) {
                val bh = (bottom - top) * v / maxV * ease
                val br = RectF(cx - bw / 2, bottom - bh, cx + bw / 2, bottom)
                bar.shader = LinearGradient(0f, br.top, 0f, br.bottom, purple, blue, Shader.TileMode.CLAMP)
                c.drawRoundRect(br, bw / 2, bw / 2, bar)
                c.drawText(v.toString(), cx, br.top - dp(4f), value)
            }
            label.color = if (i == data.lastIndex) blue else ContextCompat.getColor(context, R.color.ios_secondary)
            c.drawText(l, cx, h - dp(3f), label)
        }
        if (k < 1f) postInvalidateOnAnimation()
    }
}

/** Confeti a pantalla completa cuando termina una lista sin errores. */
class ConfettiView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private data class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float,
                         val vr: Float, val color: Int, val w: Float, val h: Float, val circle: Boolean)
    private val parts = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0L
    private var startedAt = 0L
    private val palette = intArrayOf(
        Color.parseColor("#FF3B30"), Color.parseColor("#FF9500"), Color.parseColor("#FFCC00"),
        Color.parseColor("#34C759"), Color.parseColor("#007AFF"), Color.parseColor("#AF52DE"),
        Color.parseColor("#FF2D55"))

    init { isClickable = false; isFocusable = false }

    fun burst() {
        // Mientras está oculta (GONE) mide 0: primero la mostramos y lanzamos tras el layout.
        if (width == 0) {
            visibility = VISIBLE
            post { if (width > 0) burst() }
            return
        }
        parts.clear()
        val r = Random(SystemClock.uptimeMillis())
        repeat(160) {
            val ang = (-PI / 2 + (r.nextFloat() - 0.5f) * PI * 0.9).toFloat()
            val sp = dp(700f + r.nextFloat() * 900f)
            parts += P(width / 2f + (r.nextFloat() - 0.5f) * dp(60f), height * 0.62f,
                cos(ang) * sp, sin(ang) * sp, r.nextFloat() * 360f, (r.nextFloat() - 0.5f) * 720f,
                palette[r.nextInt(palette.size)], dp(6f + r.nextFloat() * 6f), dp(10f + r.nextFloat() * 8f),
                r.nextFloat() < 0.3f)
        }
        last = SystemClock.uptimeMillis(); startedAt = last
        visibility = VISIBLE
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (parts.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val dt = ((now - last) / 1000f).coerceAtMost(0.05f); last = now
        val life = (now - startedAt) / 1000f
        val g = dp(1400f)
        val alpha = if (life > 2.2f) (255 * (1 - (life - 2.2f) / 0.8f)).toInt().coerceIn(0, 255) else 255
        for (p in parts) {
            p.vy += g * dt; p.vx *= 0.985f; p.vy *= 0.985f
            p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt
            paint.color = p.color; paint.alpha = alpha
            c.save(); c.translate(p.x, p.y); c.rotate(p.rot)
            if (p.circle) c.drawCircle(0f, 0f, p.w / 2, paint)
            else c.drawRoundRect(-p.w / 2, -p.h / 2, p.w / 2, p.h / 2, dp(2f), dp(2f), paint)
            c.restore()
        }
        if (life < 3f) postInvalidateOnAnimation() else { parts.clear(); visibility = GONE }
    }
}

/** Contenedor que dibuja un brillo diagonal que recorre el botón mientras descarga. */
class ShimmerFrame @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    var shimmering = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val m = Matrix()
    private var shader: LinearGradient? = null

    init { setWillNotDraw(false) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), dp(16f), dp(16f), Path.Direction.CW)
        shader = LinearGradient(0f, 0f, w * 0.35f, h.toFloat(),
            intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        paint.shader = shader
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (!shimmering || width == 0) return
        val cycle = SystemClock.uptimeMillis() % 3000
        if (cycle > 1100) { postInvalidateDelayed(3000 - cycle); return }   // pausa entre destellos
        val t = cycle / 1100f
        m.setTranslate(-width * 0.4f + t * width * 1.6f, 0f)
        shader?.setLocalMatrix(m)
        c.save(); c.clipPath(clip)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        c.restore()
        postInvalidateDelayed(33)
    }
}
