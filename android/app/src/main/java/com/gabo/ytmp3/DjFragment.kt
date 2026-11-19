package com.gabo.ytmp3

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

/** Disco de vinilo que gira (botón "Saltar" y botón DJ de las playlists). */
class VinylView(ctx: Context, var tint: Int = BRAND_RED) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var angle = 0f
    private var speed = 40f          // grados por segundo
    private var last = SystemClock.uptimeMillis()
    var spinning = true

    fun kick() {
        speed = 900f
        animate().cancel(); scaleX = 0.78f; scaleY = 0.78f
        animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3.5f)).start()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis(); val dt = (now - last) / 1000f; last = now
        if (spinning) angle = (angle + speed * dt) % 360f
        speed += (40f - speed) * (dt * 2.2f).coerceAtMost(1f)
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) * 0.96f
        c.save(); c.rotate(angle, cx, cy)
        p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f, 0xFF3A3A3A.toInt(), 0xFF050505.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.012f; p.color = 0x22FFFFFF
        var k = 0.92f; while (k > 0.42f) { c.drawCircle(cx, cy, r * k, p); k -= 0.07f }
        p.color = 0x55FFFFFF; p.strokeWidth = r * 0.04f
        c.drawArc(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, -60f, 40f, false, p)
        p.style = Paint.Style.FILL; p.color = tint
        c.drawCircle(cx, cy, r * 0.34f, p)
        p.color = 0x33FFFFFF; c.drawCircle(cx - r * 0.1f, cy - r * 0.1f, r * 0.12f, p)
        p.color = Color.BLACK; c.drawCircle(cx, cy, r * 0.05f, p)
        c.restore()
        if (isShown) postInvalidateOnAnimation()
    }
}

/** Fondo del DJ: visualizador OpenGL, o el caleidoscopio simple en modo ahorro. */
private fun djBackground(c: Context, mood: Mood?): View =
    if (Prefs2(c).saver) KaleidoView(c).apply { seed = (mood?.ordinal ?: 7).toLong() + 99; running = true }
    else VizView(c).apply { hue = mood?.hue; djStyle = true }

/** "¿Cómo te sientes hoy?" con burbujas de colores. */
class MoodFragment : Fragment() {
    private var bg: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val c = requireContext()
        val root = FrameLayout(c).apply { isClickable = true; setBackgroundColor(Color.BLACK) }
        bg = djBackground(c, null).also { root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(View(c).apply { setBackgroundColor(0x88000000.toInt()) }, FrameLayout.LayoutParams(MATCH, MATCH))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(c.icon(R.drawable.ic_expand, 44, Color.WHITE, 6).apply { setOnClickListener { it.haptic(); close() } },
            lp(WRAP, WRAP).apply { gravity = Gravity.START; marginStart = c.dp(8) })
        col.addView(c.text("¿Cómo te sientes hoy?", 28f, Color.WHITE, 2, 2).apply { gravity = Gravity.CENTER; setPadding(c.dp(24), c.dp(10), c.dp(24), c.dp(4)) })
        col.addView(c.text("Elige y el DJ arma la sesión perfecta", 15f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER })
        val grid = GridLayout(c).apply { columnCount = 3; setPadding(c.dp(10), c.dp(18), c.dp(10), c.dp(10)); layoutParams = android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL) }
        val bubbles = ArrayList<View>()
        Mood.entries.forEachIndexed { i, m ->
            val b = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; colors = intArrayOf(m.color, darker(m.color, 0.55f)); gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = c.dp(60).toFloat() }
                elevation = c.dp(8).toFloat()
                addView(c.text(m.emoji, 30f, Color.WHITE).apply { gravity = Gravity.CENTER })
                addView(c.text(m.label, if (m.label.length > 10) 10f else 12f, Color.WHITE, 2, 2).apply { gravity = Gravity.CENTER; setPadding(c.dp(4), 0, c.dp(4), 0) })
                setOnClickListener { v -> v.haptic(true); pick(m, v, bubbles) }
            }
            bubbles += b
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = c.dp(98); height = c.dp(98); setMargins(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
                columnSpec = GridLayout.spec(i % 3, GridLayout.CENTER)
            })
            // flotan suavemente, cada una a su ritmo
            ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
                duration = 2600L + i * 170L; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a -> b.translationY = sin(a.animatedValue as Float) * c.dp(6); b.rotation = sin((a.animatedValue as Float) * 0.5f) * 3f }
                start()
            }
        }
        col.addView(android.widget.ScrollView(c).apply { addView(android.widget.FrameLayout(c).apply { addView(grid) }) }, lp(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars()); col.setPadding(0, b.top, 0, b.bottom); ins
        }
        return root
    }

    override fun onResume() { super.onResume(); (bg as? VizView)?.start() }
    override fun onPause() { (bg as? VizView)?.stop(); super.onPause() }

    private fun close() = parentFragmentManager.popBackStack()

    private fun pick(m: Mood, v: View, all: List<View>) {
        all.filter { it !== v }.forEach { it.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(250).start() }
        v.animate().scaleX(2.6f).scaleY(2.6f).alpha(0f).setDuration(420).withEndAction {
            val a = activity as? MainActivity ?: return@withEndAction
            parentFragmentManager.popBackStack()
            a.openDj(mood = m, tracks = null, start = null, name = "${m.emoji} ${m.label}")
        }.start()
    }
}

/** Pantalla del Modo DJ. */
class DjFragment : Fragment() {
    companion object {
        var pendingTracks: List<Track>? = null
        var pendingStart: Track? = null
        fun new(mood: Mood?, name: String, plId: Long) = DjFragment().apply {
            arguments = Bundle().apply { putString("mood", mood?.name); putString("name", name); putLong("pl", plId) }
        }
        private val phrases = listOf("Creando tu DJ…", "Imaginando pistas…", "Buscando el ritmo perfecto…", "Afinando los coros…",
            "Encontrando el clímax de cada canción…", "Mezclando vibras…", "Calentando los platos…", "Preparando las transiciones…")
    }

    private val mood get() = arguments?.getString("mood")?.let { runCatching { Mood.valueOf(it) }.getOrNull() }
    private var bg: View? = null
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var nextText: TextView
    private lateinit var vinyl: VinylView
    private lateinit var loading: LinearLayout
    private lateinit var phrase: TextView
    private lateinit var prog: TextView
    private val listener: () -> Unit = { render() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val c = requireContext()
        val m = mood
        val root = FrameLayout(c).apply { isClickable = true; setBackgroundColor(Color.BLACK) }
        bg = djBackground(c, m).also { root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88000000.toInt(), 0x00000000, 0x00000000, 0xDD000000.toInt())) },
            FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(20), 0, c.dp(20), 0) }
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(c.icon(R.drawable.ic_expand, 44, Color.WHITE, 6).apply { setOnClickListener { it.haptic(); parentFragmentManager.popBackStack() } })
        val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        mid.addView(c.text("MODO DJ", 11f, 0xCCFFFFFF.toInt(), 2).apply { letterSpacing = 0.2f; gravity = Gravity.CENTER })
        mid.addView(c.text(arguments?.getString("name") ?: "Aleatorio total", 15f, Color.WHITE, 2).apply { gravity = Gravity.CENTER })
        top.addView(mid, lp(0, WRAP, 1f))
        top.addView(c.icon(R.drawable.ic_more_h, 44, Color.WHITE, 9).apply { setOnClickListener { it.haptic(); showQueue() } })
        col.addView(top, lp(MATCH, WRAP).apply { topMargin = c.dp(6) })
        col.addView(View(c), lp(1, 0, 1f))
        title = c.text("", 26f, Color.WHITE, 2, 2)
        artist = c.text("", 16f, 0xCCFFFFFF.toInt())
        nextText = c.text("", 13f, 0xAAFFFFFF.toInt(), 1).apply { setPadding(0, c.dp(10), 0, 0) }
        col.addView(title); col.addView(artist); col.addView(nextText)
        vinyl = VinylView(c, m?.color ?: BRAND_RED).apply {
            setOnClickListener { v -> v.haptic(true); kick(); PlayerHub.service?.djNext(true) }
        }
        col.addView(vinyl, lp(c.dp(118), c.dp(118)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = c.dp(26) })
        col.addView(c.text("Saltar", 14f, Color.WHITE, 2).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(6), 0, c.dp(30)) }, lp(MATCH, WRAP))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        loading = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0x99000000.toInt()); isClickable = true
        }
        loading.addView(VinylView(c, m?.color ?: BRAND_RED), lp(c.dp(120), c.dp(120)))
        phrase = c.text(phrases[0], 22f, Color.WHITE, 2).apply { gravity = Gravity.CENTER; setPadding(c.dp(24), c.dp(22), c.dp(24), c.dp(6)) }
        prog = c.text("", 14f, 0xBBFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        loading.addView(phrase, lp(MATCH, WRAP)); loading.addView(prog, lp(MATCH, WRAP))
        root.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars()); col.setPadding(c.dp(20), b.top, c.dp(20), b.bottom); ins
        }
        return root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        if (arguments?.getBoolean("start", true) == false) { loading.visibility = View.GONE; return }
        viewLifecycleOwner.lifecycleScope.launch { prepareAndStart() }
        viewLifecycleOwner.lifecycleScope.launch {
            var i = 0
            while (loading.visibility == View.VISIBLE) {
                delay(1700); i++
                phrase.animate().alpha(0f).setDuration(220).withEndAction {
                    phrase.text = phrases[i % phrases.size]; phrase.animate().alpha(1f).setDuration(260).start()
                }.start()
            }
        }
    }

    private suspend fun prepareAndStart() {
        val c = requireContext().applicationContext
        val dao = Library.dao(c)
        val tracks = pendingTracks ?: dao.allTracksNow()
        val start = pendingStart
        pendingTracks = null; pendingStart = null
        if (tracks.size < 2) {
            Toast.makeText(c, "Necesitas al menos 2 canciones para el Modo DJ", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack(); return
        }
        // Analizar lo que falte (hasta 20 ahora; el resto sigue en segundo plano)
        val pending = tracks.filter { it.analyzed == 0 }.shuffled().take(20).let { list -> (listOfNotNull(start?.takeIf { it.analyzed == 0 }) + list).distinctBy { it.id } }
        pending.forEachIndexed { i, t ->
            prog.text = "Analizando canciones · ${i + 1} de ${pending.size}"
            Analyzer.analyzeAndSave(c, t)
        }
        Analyzer.kick(c)
        val fresh = dao.tracksByIds(tracks.map { it.id }).associateBy { it.id }
        val list = tracks.mapNotNull { fresh[it.id] }
        val scores = DjBrain.learned(c, list.map { it.id }, mood)
        val m = mood
        if (m != null) {
            val good = list.count { DjBrain.score(it, m, scores[it.id] ?: 0f) >= 4f }
            val hint = DjBrain.genreHint(m)
            if (good < 8 && hint != null) Toast.makeText(c, "Tu DJ ${m.label} suena mejor con más $hint ${m.emoji}. Descárgala en la pestaña Descargar.", Toast.LENGTH_LONG).show()
        }
        PlayerHub.with(requireActivity()) { s ->
            s.startDj(list, m, start?.let { st -> list.firstOrNull { it.id == st.id } }, arguments?.getString("name") ?: "Modo DJ", scores,
                arguments?.getLong("pl", -4L) ?: -4L)
            loading.animate().alpha(0f).setDuration(400).withEndAction { loading.visibility = View.GONE }.start()
        }
    }

    override fun onStart() { super.onStart(); PlaybackService.addListener(listener); render(); view?.post(tick) }
    override fun onStop() { PlaybackService.removeListener(listener); view?.removeCallbacks(tick); super.onStop() }
    override fun onResume() { super.onResume(); (bg as? VizView)?.start() }
    override fun onPause() { (bg as? VizView)?.stop(); super.onPause() }

    private val tick = object : Runnable {
        override fun run() {
            val s = PlayerHub.service
            val v = bg as? VizView
            if (s != null && v != null) {
                val d = s.active.duration
                if (d > 0) v.progress = s.active.currentPosition.toFloat() / d
            }
            view?.postDelayed(this, 250)
        }
    }

    private var lastId = -1L
    private fun render() {
        val s = PlayerHub.service ?: return
        val t = s.current() ?: return
        title.text = t.title
        artist.text = t.artist.ifEmpty { "" }
        nextText.text = s.djUpcoming.firstOrNull()?.let { "Sigue: ${it.title}" } ?: ""
        if (t.id != lastId) { lastId = t.id; (bg as? VizView)?.envelope = Analyzer.envelope(t) }
    }

    private fun showQueue() {
        val a = activity as? AppCompatActivity ?: return
        val s = PlayerHub.service ?: return
        val up = s.djUpcoming
        val opts = up.map { "♪ ${it.title}" } + listOf("Salir del Modo DJ")
        ActionSheet.show(a, if (up.isEmpty()) "Modo DJ" else "Lo que viene", opts, destructive = setOf(opts.size - 1)) { i ->
            if (i == opts.size - 1) { s.exitDj(); parentFragmentManager.popBackStack() }
        }
    }
}
