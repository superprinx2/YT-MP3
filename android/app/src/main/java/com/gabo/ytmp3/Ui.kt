package com.gabo.ytmp3

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.load
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.col(id: Int) = ContextCompat.getColor(this, id)

object Fonts {
    fun regular(c: Context) = ResourcesCompat.getFont(c, R.font.inter_regular)
    fun semibold(c: Context) = ResourcesCompat.getFont(c, R.font.inter_semibold)
    fun bold(c: Context) = ResourcesCompat.getFont(c, R.font.inter_bold)
}

const val BRAND_RED = 0xFFE11D2A.toInt()

fun Context.text(s: CharSequence, size: Float, color: Int, weight: Int = 0, lines: Int = 1): TextView = TextView(this).apply {
    text = s; textSize = size; setTextColor(color)
    typeface = when (weight) { 2 -> Fonts.bold(context); 1 -> Fonts.semibold(context); else -> Fonts.regular(context) }
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
}

fun Context.icon(res: Int, sizeDp: Int, tint: Int, padDp: Int = 0): ImageView = ImageView(this).apply {
    setImageResource(res); setColorFilter(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    if (padDp > 0) setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    scaleType = ImageView.ScaleType.FIT_CENTER
}

fun roundRect(color: Int, radiusPx: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }
fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Carga la portada de una canción (o su caleidoscopio). */
fun ImageView.cover(t: Track, radiusDp: Float = 6f) {
    val f = Covers.fileFor(context, t)
    clipToOutline = true
    background = roundRect(0xFF222222.toInt(), context.dp(radiusDp).toFloat())
    scaleType = ImageView.ScaleType.CENTER_CROP
    load(f) { crossfade(true) }
}

object Art {
    /** Color dominante de una imagen (para degradados al estilo Spotify). */
    suspend fun color(file: File?, fallback: Int = 0xFF3A1014.toInt()): Int = withContext(Dispatchers.IO) {
        val f = file?.takeIf { it.exists() } ?: return@withContext fallback
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val b = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return@withContext fallback
        val p = Palette.from(b).maximumColorCount(16).generate()
        b.recycle()
        val c = p.getVibrantColor(p.getDominantColor(fallback))
        val sw = p.dominantSwatch?.rgb ?: c
        // mezclar dominante y vibrante y oscurecer un poco para que el texto blanco se lea
        val mix = ColorUtils.blendARGB(sw, c, 0.5f)
        val hsl = FloatArray(3); ColorUtils.colorToHSL(mix, hsl)
        hsl[2] = hsl[2].coerceIn(0.18f, 0.42f); hsl[1] = hsl[1].coerceAtMost(0.75f)
        ColorUtils.HSLToColor(hsl)
    }

    /** Portada de una playlist: la elegida, un mosaico de 4 o la primera canción. */
    suspend fun playlistCover(ctx: Context, row: PlaylistRow): File = withContext(Dispatchers.IO) {
        row.coverPath?.let(::File)?.takeIf { it.exists() }?.let { return@withContext it }
        val covers = row.covers?.split('|')?.filter { File(it).exists() }.orEmpty()
        if (covers.size >= 4 && !Prefs2(ctx).kaleidoAll) {
            val f = File(File(ctx.cacheDir, "mosaic").apply { mkdirs() }, "p${row.id}_${covers.joinToString().hashCode()}.jpg")
            if (!f.exists()) {
                val size = 400; val half = size / 2
                val out = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
                val cv = Canvas(out)
                covers.take(4).forEachIndexed { i, p ->
                    val b = BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return@forEachIndexed
                    val s = minOf(b.width, b.height)
                    val src = Rect((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
                    val x = (i % 2) * half; val y = (i / 2) * half
                    cv.drawBitmap(b, src, Rect(x, y, x + half, y + half), null)
                    b.recycle()
                }
                f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
            return@withContext f
        }
        if (covers.isNotEmpty() && !Prefs2(ctx).kaleidoAll) return@withContext File(covers[0])
        KaleidoRenderer.artFile(ctx, (row.firstTrackId ?: (-row.id - 1000)))
    }
}

/** Conexión con el servicio de reproducción (misma app: se usa la instancia directa). */
object PlayerHub {
    private var future: ListenableFuture<MediaController>? = null

    fun connect(ctx: Context) {
        if (future != null) return
        val app = ctx.applicationContext
        future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    fun with(ctx: Context, block: (PlaybackService) -> Unit) {
        PlaybackService.instance?.let { block(it); return }
        connect(ctx)
        future!!.addListener({ PlaybackService.instance?.let(block) }, ContextCompat.getMainExecutor(ctx))
    }

    val service get() = PlaybackService.instance
}

/** Fila de lista con portada, título y subtítulo. */
fun Context.listRow(cover: View, title: String, sub: String, titleColor: Int, subColor: Int, coverDp: Int = 52): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(6), dp(12), dp(6))
        addView(cover, lp(dp(coverDp), dp(coverDp)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(text(title, 16f, titleColor, 1).apply { tag = "title" })
        texts.addView(text(sub, 13f, subColor).apply { tag = "sub"; setPadding(0, dp(2), 0, 0) })
        addView(texts, lp(0, WRAP, 1f))
    }

fun View.frameLp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

fun darker(c: Int, f: Float = 0.6f): Int = ColorUtils.blendARGB(c, Color.BLACK, 1 - f)
