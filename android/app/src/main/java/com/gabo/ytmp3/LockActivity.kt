package com.gabo.ytmp3

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import coil.load
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de bloqueo propia de DownSong: la portada a pantalla completa (o el visualizador),
 * la hora y todos los controles. "Desliza hacia arriba" la cierra y queda el bloqueo normal
 * del celular (PIN/huella intactos). Funciona en todas las marcas, incluido Huawei.
 */
class LockActivity : AppCompatActivity() {
    private lateinit var bg: ImageView
    private var viz: VizView? = null
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var play: ImageView
    private lateinit var shuffle: ImageView
    private lateinit var repeat: ImageView
    private lateinit var seek: SeekBar
    private var lastId = -1L
    private val listener: () -> Unit = { render() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
        }
        val c = this
        val root = FrameLayout(c).apply { setBackgroundColor(Color.BLACK) }
        bg = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))
        run {
            viz = VizView(c).apply { visibility = View.GONE }
            root.addView(viz, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x88000000.toInt(), 0x22000000, 0x55000000, 0xEE000000.toInt()))
        }, FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(28), dp(70), dp(28), dp(40)) }
        clock = text("", 76f, Color.WHITE, 0).apply { gravity = Gravity.CENTER; typeface = Fonts.semibold(c) }
        date = text("", 16f, 0xDDFFFFFF.toInt(), 1).apply { gravity = Gravity.CENTER }
        col.addView(clock); col.addView(date)
        col.addView(View(c), lp(1, 0, 1f))
        title = text("", 24f, Color.WHITE, 2, 2)
        artist = text("", 16f, 0xCCFFFFFF.toInt())
        col.addView(title, lp(MATCH, WRAP)); col.addView(artist, lp(MATCH, WRAP))
        seek = SeekBar(c).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x4DFFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    val sv = PlayerHub.service ?: return; val d = sv.active.duration
                    if (d > 0) sv.seekTo(d * s.progress / 1000)
                }
            })
        }
        col.addView(seek, lp(MATCH, WRAP).apply { topMargin = dp(14); setMargins(-dp(14), dp(14), -dp(14), 0) })
        val ctr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shuffle = icon(R.drawable.ic_shuffle, 48, Color.WHITE, 11).apply { setOnClickListener { it.haptic(); PlayerHub.service?.let { s -> s.setShuffle(!s.active.shuffleModeEnabled) } } }
        val prev = icon(R.drawable.ic_prev, 58, Color.WHITE, 11).apply { setOnClickListener { it.haptic(); PlayerHub.service?.prev() } }
        play = icon(R.drawable.ic_play, 72, Color.BLACK, 17).apply { background = oval(Color.WHITE); setOnClickListener { it.haptic(); it.bounce(); PlayerHub.service?.togglePlay() } }
        val next = icon(R.drawable.ic_next, 58, Color.WHITE, 11).apply { setOnClickListener { it.haptic(); PlayerHub.service?.next() } }
        repeat = icon(R.drawable.ic_repeat, 48, Color.WHITE, 11).apply { setOnClickListener { it.haptic(); PlayerHub.service?.cycleRepeat() } }
        listOf(shuffle, prev, play, next, repeat).forEachIndexed { i, v -> if (i > 0) ctr.addView(View(c), lp(0, 1, 1f)); ctr.addView(v) }
        col.addView(ctr, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        col.addView(text("︿\nDesliza hacia arriba para desbloquear", 13f, 0xBBFFFFFF.toInt(), 1, 2).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(28), 0, 0)
        })
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        var downY = 0f
        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_MOVE -> v.translationY = (e.rawY - downY).coerceAtMost(0f) * 0.6f
                MotionEvent.ACTION_UP -> if (downY - e.rawY > dp(140)) unlock() else v.animate().translationY(0f).setDuration(200).start()
            }
            true
        }
        setContentView(root)
    }

    private fun unlock() {
        window.decorView.animate().alpha(0f).setDuration(180).withEndAction { finish(); overridePendingTransition(0, 0) }.start()
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.addListener(listener)
        lastId = -1; render(); clockTick.run()
    }

    override fun onStop() {
        PlaybackService.removeListener(listener)
        clock.removeCallbacks(clockTick)
        viz?.stop()
        super.onStop()
    }

    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es")).format(now).replaceFirstChar { it.uppercase() }
            PlayerHub.service?.active?.let { p -> if (p.duration > 0) seek.progress = (p.currentPosition * 1000 / p.duration).toInt() }
            clock.postDelayed(this, 1000)
        }
    }

    private fun render() {
        val s = PlayerHub.service
        val t = s?.current()
        if (s == null || t == null) { finish(); return }
        title.text = t.title
        artist.text = t.artist.ifEmpty { s.playlistName }
        play.setImageResource(if (s.active.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        shuffle.setColorFilter(if (s.active.shuffleModeEnabled) BRAND_RED else Color.WHITE)
        repeat.setImageResource(if (s.active.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeat.setColorFilter(if (s.active.repeatMode == Player.REPEAT_MODE_OFF) Color.WHITE else BRAND_RED)
        if (t.id != lastId) {
            lastId = t.id
            val useViz = viz != null && Covers.usesKaleido(this, t)
            viz?.visibility = if (useViz) View.VISIBLE else View.GONE
            if (useViz) viz?.start() else viz?.stop()
            lifecycleScope.launch { bg.load(Covers.fileFor(this@LockActivity, t)) { crossfade(true) } }
        }
    }
}
