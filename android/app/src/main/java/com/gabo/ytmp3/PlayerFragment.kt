package com.gabo.ytmp3

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Reproductor a pantalla completa al estilo Spotify. */
@OptIn(UnstableApi::class)
class PlayerFragment : Fragment() {

    private lateinit var root: FrameLayout
    private lateinit var art: FrameLayout
    private lateinit var coverImg: ImageView
    private lateinit var kaleido: KaleidoView
    private lateinit var bgKaleido: KaleidoView
    private var viz: VizView? = null
    private lateinit var scrim: View
    private lateinit var video: PlayerView
    private lateinit var fromText: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var videoBtn: ImageView
    private lateinit var seek: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var shuffleBtn: ImageView
    private lateinit var repeatBtn: ImageView
    private lateinit var playBtn: ImageView
    private var lastId = -1L
    private var color = 0xFF3A1014.toInt()
    private var dragging = false
    private val listener: () -> Unit = { render() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val c = requireContext()
        root = FrameLayout(c).apply { isClickable = true; setBackgroundColor(Color.BLACK) }
        // Caleidoscopio a pantalla completa (canciones sin portada)
        bgKaleido = KaleidoView(c).apply { visibility = View.GONE }
        root.addView(bgKaleido, FrameLayout.LayoutParams(MATCH, MATCH))
        viz = VizView(c).apply { visibility = View.GONE }; root.addView(viz, FrameLayout.LayoutParams(MATCH, MATCH))
        scrim = View(c).apply {
            visibility = View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x00000000, 0x00000000, 0xCC000000.toInt()))
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(20), 0, c.dp(20), 0) }
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // barra superior
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(c.icon(R.drawable.ic_expand, 44, Color.WHITE, 6).apply { setOnClickListener { it.haptic(); close() } })
        val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        mid.addView(c.text("REPRODUCIENDO DESDE PLAYLIST", 10f, 0xCCFFFFFF.toInt(), 1).apply { letterSpacing = 0.08f; gravity = Gravity.CENTER })
        fromText = c.text("", 13f, Color.WHITE, 2).apply { gravity = Gravity.CENTER }
        mid.addView(fromText)
        top.addView(mid, lp(0, WRAP, 1f))
        top.addView(c.icon(R.drawable.ic_more_h, 44, Color.WHITE, 9).apply { setOnClickListener { it.haptic(); menu() } })
        col.addView(top, lp(MATCH, WRAP).apply { topMargin = c.dp(8) })

        // portada / video / caleidoscopio
        art = FrameLayout(c)
        coverImg = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = roundRect(0xFF222222.toInt(), c.dp(8).toFloat())
        }
        kaleido = KaleidoView(c).apply { clipToOutline = true; background = roundRect(Color.BLACK, c.dp(8).toFloat()) }
        video = PlayerView(c).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        art.addView(coverImg, FrameLayout.LayoutParams(MATCH, MATCH))
        art.addView(kaleido, FrameLayout.LayoutParams(MATCH, MATCH))
        art.addView(video, FrameLayout.LayoutParams(MATCH, MATCH))
        col.addView(FrameLayout(c).apply {
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        }, lp(MATCH, 0, 1f).apply { topMargin = c.dp(24); bottomMargin = c.dp(24) })
        art.post { val sz = minOf((art.parent as View).width, (art.parent as View).height); art.layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.CENTER) }

        // título + botón de video
        val info = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        title = c.text("", 22f, Color.WHITE, 2)
        artist = c.text("", 15f, 0xB3FFFFFF.toInt())
        texts.addView(title); texts.addView(artist)
        info.addView(texts, lp(0, WRAP, 1f))
        videoBtn = c.icon(R.drawable.ic_videocam, 44, Color.WHITE, 9).apply { setOnClickListener { it.haptic(); toggleVideo() } }
        info.addView(videoBtn)
        col.addView(info)

        // barra de progreso
        seek = SeekBar(c).apply {
            max = 1000; setPadding(c.dp(4), c.dp(12), c.dp(4), c.dp(6))
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x4DFFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) PlayerHub.service?.durMs?.takeIf { it > 0 }?.let { posText.text = fmt(it * p / 1000) }
                }
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    val s = PlayerHub.service ?: return
                    val d = s.durMs
                    if (d > 0) s.seekTo(d * sb.progress / 1000)
                }
            })
        }
        col.addView(seek, lp(MATCH, WRAP).apply { setMargins(-c.dp(16), 0, -c.dp(16), 0) })
        val times = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        posText = c.text("0:00", 12f, 0xB3FFFFFF.toInt()); durText = c.text("0:00", 12f, 0xB3FFFFFF.toInt())
        times.addView(posText, lp(0, WRAP, 1f)); times.addView(durText)
        col.addView(times)

        // controles
        val ctr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shuffleBtn = c.icon(R.drawable.ic_shuffle, 48, Color.WHITE, 11).apply {
            setOnClickListener { it.haptic(); PlayerHub.service?.let { s -> s.setShuffle(!s.active.shuffleModeEnabled) } }
        }
        val prev = c.icon(R.drawable.ic_prev, 56, Color.WHITE, 10).apply { setOnClickListener { it.haptic(); PlayerHub.service?.prev() } }
        playBtn = c.icon(R.drawable.ic_play, 68, Color.BLACK, 16).apply {
            background = oval(Color.WHITE)
            setOnClickListener { it.haptic(true); it.bounce(); PlayerHub.service?.togglePlay() }
        }
        val next = c.icon(R.drawable.ic_next, 56, Color.WHITE, 10).apply { setOnClickListener { it.haptic(); PlayerHub.service?.next() } }
        repeatBtn = c.icon(R.drawable.ic_repeat, 48, Color.WHITE, 11).apply { setOnClickListener { it.haptic(); PlayerHub.service?.cycleRepeat() } }
        listOf(shuffleBtn, prev, playBtn, next, repeatBtn).forEachIndexed { i, v ->
            if (i > 0) ctr.addView(View(c), lp(0, 1, 1f))
            ctr.addView(v)
        }
        col.addView(ctr, lp(MATCH, WRAP).apply { topMargin = c.dp(8); bottomMargin = c.dp(28) })

        // gestos: deslizar abajo cierra; deslizar la portada a los lados cambia de canción
        val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x); val dy = e2.y - (e1?.y ?: e2.y)
                if (dy > c.dp(80) && abs(vy) > abs(vx)) { close(); return true }
                return false
            }
        })
        root.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
        val artGd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x); val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(dx) > abs(dy) && abs(dx) > c.dp(50)) { swipe(if (dx < 0) 1 else -1); return true }
                if (dy > c.dp(80)) { close(); return true }
                return false
            }
        })
        art.setOnTouchListener { _, e -> artGd.onTouchEvent(e) }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            col.setPadding(c.dp(20), b.top, c.dp(20), b.bottom)
            insets
        }
        return root
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.addListener(listener)
        lastId = -1
        render()
        root.post(tick)
    }

    override fun onStop() {
        PlaybackService.removeListener(listener)
        root.removeCallbacks(tick)
        kaleido.running = false
        bgKaleido.running = false
        viz?.stop(); viz?.visibility = View.GONE
        video.player = null
        super.onStop()
    }

    private fun close() = (activity as? MainActivity)?.closePlayer()

    private fun swipe(dir: Int) {
        val s = PlayerHub.service ?: return
        if (dir > 0 && !s.active.hasNextMediaItem()) return
        val w = art.width.toFloat()
        art.animate().translationX(-dir * w * 0.6f).alpha(0f).setDuration(140).withEndAction {
            if (dir > 0) s.next() else s.prev()
            art.translationX = dir * w * 0.6f
            art.animate().translationX(0f).alpha(1f).setDuration(200).start()
        }.start()
    }

    private fun toggleVideo() {
        val s = PlayerHub.service ?: return
        val v = !s.showVideo
        s.setShowVideo(v)
        val pid = s.playlistId
        if (pid != null) lifecycleScope.launch {
            Library.dao(requireContext()).playlist(pid)?.let { Library.dao(requireContext()).updatePlaylist(it.copy(showVideo = v)) }
        }
    }

    private fun menu() {
        val s = PlayerHub.service ?: return
        val t = s.current() ?: return
        val a = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        ActionSheet.show(a, t.title, listOf("Agregar a otra playlist")) {
            PlaylistPicker.show(a, "Agregar a playlist", emptySet(), null, "Agregar") { ids, _ ->
                a.lifecycleScope.launch { Library.addToPlaylists(a, listOf(t.id), ids) }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = PlayerHub.service
            if (s != null && !dragging) {
                val d = s.durMs; val p = s.active.currentPosition
                if (d > 0) { seek.progress = (p * 1000 / d).toInt(); durText.text = fmt(d) }
                posText.text = fmt(p)
            }
            root.postDelayed(this, 500)
        }
    }

    private fun fmt(ms: Long) = Util.fmtDuration(ms / 1000.0).ifEmpty { "0:00" }

    private fun render() {
        val s = PlayerHub.service
        val t = s?.current()
        if (s == null || t == null) { if (isAdded) close(); return }
        val c = requireContext()
        fromText.text = s.playlistName.ifEmpty { "Todas las canciones" }
        title.text = t.title
        artist.text = t.artist.ifEmpty { s.playlistName }
        playBtn.setImageResource(if (s.active.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        shuffleBtn.setColorFilter(if (s.active.shuffleModeEnabled) BRAND_RED else Color.WHITE)
        repeatBtn.setImageResource(if (s.active.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatBtn.setColorFilter(if (s.active.repeatMode == Player.REPEAT_MODE_OFF) Color.WHITE else BRAND_RED)
        videoBtn.visibility = if (t.hasVideo) View.VISIBLE else View.GONE
        videoBtn.setColorFilter(if (s.showVideo) BRAND_RED else Color.WHITE)

        val showVideo = s.showVideo && t.hasVideo
        video.visibility = if (showVideo) View.VISIBLE else View.GONE
        if (showVideo) { if (video.player !== s.active) video.player = s.active } else video.player = null
        val useK = Covers.usesKaleido(c, t) && Prefs2(c).kaleidoNoCover
        val full = useK && !showVideo
        kaleido.visibility = View.GONE
        val v = viz
        if (v != null) {
            bgKaleido.visibility = View.GONE
            if (full && v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; v.start() }
            if (!full && v.visibility == View.VISIBLE) { v.stop(); v.visibility = View.GONE }
            scrim.visibility = v.visibility
        } else {
            bgKaleido.visibility = if (full) View.VISIBLE else View.GONE
            scrim.visibility = bgKaleido.visibility
            bgKaleido.seed = t.id
            bgKaleido.running = full && s.active.isPlaying
        }
        coverImg.visibility = if (!useK && !showVideo) View.VISIBLE else View.GONE

        if (t.id != lastId) {
            lastId = t.id
            val f = Covers.fileFor(c, t)
            coverImg.load(f)
            lifecycleScope.launch {
                color = Art.color(f)
                root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(color, darker(color, 0.55f), 0xFF121212.toInt()))
            }
        }
    }
}
