package com.gabo.ytmp3

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.audiofx.Equalizer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ln

/** Ecualizador al estilo Spotify: perfiles que se pueden ajustar arrastrando la curva. */
object Eq {
    val freqs = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
    val labels = listOf("60 Hz", "230 Hz", "910 Hz", "3,6 kHz", "14 kHz")
    val presets = linkedMapOf(
        "Normal" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
        "Graves" to floatArrayOf(6f, 4f, 0f, 0f, 0f),
        "Pop" to floatArrayOf(-1f, 2f, 4f, 2f, -1f),
        "Reguetón" to floatArrayOf(5f, 3f, 0f, 1f, 2f),
        "Rock" to floatArrayOf(4f, 2f, -1f, 2f, 4f),
        "Electrónica" to floatArrayOf(5f, 2f, 0f, 2f, 4f),
        "Hip-hop" to floatArrayOf(5f, 4f, 0f, -1f, 2f),
        "Voces" to floatArrayOf(-2f, 0f, 4f, 3f, 1f),
        "Acústico" to floatArrayOf(3f, 1f, 1f, 2f, 3f),
        "Latina" to floatArrayOf(3f, 0f, 0f, 2f, 4f),
    )

    fun bands(p: Prefs2, preset: String = p.eqPreset): FloatArray = p.eqBands(preset) ?: presets[preset]?.copyOf() ?: FloatArray(5)

    fun apply(eq: Equalizer, p: Prefs2) {
        runCatching {
            val on = p.eqOn
            val b = bands(p)
            val range = eq.bandLevelRange
            for (i in 0 until eq.numberOfBands) {
                val hz = eq.getCenterFreq(i.toShort()) / 1000f
                val db = interp(b, hz)
                eq.setBandLevel(i.toShort(), (db * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
            eq.enabled = on && b.any { abs(it) > 0.05f }
        }
    }

    private fun interp(b: FloatArray, hz: Float): Float {
        val x = ln(hz.coerceIn(freqs.first(), freqs.last()))
        for (i in 0 until freqs.size - 1) {
            val a = ln(freqs[i]); val c = ln(freqs[i + 1])
            if (x in a..c) return b[i] + (b[i + 1] - b[i]) * ((x - a) / (c - a))
        }
        return b.last()
    }
}

/** Curva del ecualizador: se arrastran los puntos hacia arriba o abajo (±12 dB). */
class EqCurveView(ctx: Context) : View(ctx) {
    var bands = FloatArray(5)
        set(v) { field = v.copyOf(); invalidate() }
    var onChange: ((FloatArray) -> Unit)? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); color = BRAND_RED }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val grid = Paint().apply { color = ctx.col(R.color.ios_separator); strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.col(R.color.ios_secondary); textSize = ctx.dp(11).toFloat(); textAlign = Paint.Align.CENTER }
    private var drag = -1

    private fun px(i: Int) = paddingLeft + (width - paddingLeft - paddingRight) * (i + 0.5f) / 5f
    private fun py(db: Float): Float { val top = context.dp(10).toFloat(); val bot = height - context.dp(26).toFloat(); return top + (bot - top) * (12f - db) / 24f }

    override fun onDraw(c: Canvas) {
        val bot = height - context.dp(26).toFloat()
        c.drawLine(paddingLeft.toFloat(), py(0f), (width - paddingRight).toFloat(), py(0f), grid)
        val path = Path()
        for (i in 0 until 5) {
            val x = px(i); val y = py(bands[i])
            if (i == 0) path.moveTo(x, y) else {
                val x0 = px(i - 1); val y0 = py(bands[i - 1]); val mx = (x0 + x) / 2
                path.cubicTo(mx, y0, mx, y, x, y)
            }
        }
        val area = Path(path).apply { lineTo(px(4), bot); lineTo(px(0), bot); close() }
        fill.shader = LinearGradient(0f, 0f, 0f, bot, 0x88E11D2A.toInt(), 0x00E11D2A, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        c.drawPath(path, line)
        for (i in 0 until 5) {
            c.drawCircle(px(i), py(bands[i]), context.dp(if (i == drag) 11 else 8).toFloat(), dot)
            c.drawText(Eq.labels[i], px(i), height - context.dp(6).toFloat(), text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = (0 until 5).minByOrNull { abs(px(it) - e.x) } ?: -1
                parent?.requestDisallowInterceptTouchEvent(true)
                haptic()
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                val top = context.dp(10).toFloat(); val bot = height - context.dp(26).toFloat()
                val db = 12f - (e.y.coerceIn(top, bot) - top) / (bot - top) * 24f
                bands[drag] = (Math.round(db * 2) / 2f)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (drag >= 0) onChange?.invoke(bands.copyOf()); drag = -1; invalidate() }
        }
        return true
    }
}
