package com.gabo.ytmp3

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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

/**
 * Disco de vinilo "DownSong": negro con surcos, las ondas rojas del logo y la portada de la
 * playlist como etiqueta. Gira, refleja la luz y salta con los bajos.
 */
class VinylView(ctx: Context, var tint: Int = BRAND_RED) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var angle = 0f
    private var speed = 40f          // grados por segundo
    private var last = SystemClock.uptimeMillis()
    private var labelShader: BitmapShader? = null
    private val m = Matrix()
    var spinning = true
    /** Salta con los bajos de la música (pantalla del DJ). */
    var reactive = false
    var cover: Bitmap? = null
        set(v) { field = v; labelShader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }; invalidate() }

    fun kick() {
        speed = 900f
        animate().cancel(); scaleX = 0.78f; scaleY = 0.78f
        animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3.5f)).start()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis(); val dt = (now - last) / 1000f; last = now
        val base = if (reactive) 70f else 40f
        if (spinning) angle = (angle + speed * dt) % 360f
        speed += (base - speed) * (dt * 2.2f).coerceAtMost(1f)
        val bass = if (reactive) AudioLevels.bass else 0f
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy) * 0.94f * (1f + bass * 0.035f)
        c.save(); c.rotate(angle, cx, cy)
        // disco
        p.style = Paint.Style.FILL; p.shader = null; p.color = 0xFF0B0B0B.toInt()
        c.drawCircle(cx, cy, r, p)
        // surcos
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.006f; p.color = 0x1CFFFFFF
        var k = 0.95f; while (k > 0.5f) { c.drawCircle(cx, cy, r * k, p); k -= 0.028f }
        // ondas rojas del logo
        val glow = (0.55f + bass * 0.45f).coerceIn(0f, 1f)
        floatArrayOf(0.965f, 0.885f, 0.805f).forEachIndexed { i, rr ->
            p.strokeWidth = r * (0.016f - i * 0.003f)
            p.color = androidx.core.graphics.ColorUtils.setAlphaComponent(BRAND_RED, ((0xD0 - i * 0x40) * glow).toInt().coerceIn(0, 255))
            c.drawCircle(cx, cy, r * rr, p)
        }
        // etiqueta: portada o color con el monograma
        val lr = r * 0.46f
        p.style = Paint.Style.FILL
        val sh = labelShader
        val bmp = cover
        if (sh != null && bmp != null) {
            val sc = (lr * 2) / minOf(bmp.width, bmp.height)
            m.reset(); m.setScale(sc, sc)
            m.postTranslate(cx - bmp.width * sc / 2, cy - bmp.height * sc / 2)
            sh.setLocalMatrix(m); p.shader = sh
            c.drawCircle(cx, cy, lr, p); p.shader = null
        } else {
            p.color = tint; c.drawCircle(cx, cy, lr, p)
            p.color = 0xFFFFFFFF.toInt(); p.textAlign = Paint.Align.CENTER; p.textSize = lr * 0.62f
            p.isFakeBoldText = true
            c.drawText("DG", cx, cy + lr * 0.22f, p)
            p.isFakeBoldText = false
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.022f; p.color = BRAND_RED
        c.drawCircle(cx, cy, lr, p)
        p.style = Paint.Style.FILL; p.color = 0xFF111111.toInt(); c.drawCircle(cx, cy, r * 0.045f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.01f; p.color = 0x66FFFFFF; c.drawCircle(cx, cy, r * 0.045f, p)
        c.restore()
        // brillo de la luz (no gira: así se nota el giro del disco)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.22f; p.color = 0x16FFFFFF
        val o = r * 0.73f
        c.drawArc(cx - o, cy - o, cx + o, cy + o, -150f, 50f, false, p)
        p.color = 0x10FFFFFF
        c.drawArc(cx - o, cy - o, cx + o, cy + o, 30f, 45f, false, p)
        if (isShown) postInvalidateOnAnimation()
    }
}

/** Carga una imagen pequeña (para la etiqueta del disco). */
suspend fun loadSmallBitmap(f: java.io.File?, size: Int = 360): Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (f == null || !f.exists()) return@withContext null
    runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1; while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()
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
    private lateinit var mixText: TextView
    private lateinit var vinyl: VinylView
    private lateinit var loading: LinearLayout
    private lateinit var phrase: TextView
    private lateinit var prog: TextView
    private val listener: () -> Unit = { render() }
    private var loadingVinyl: VinylView? = null
    private var playlistCover: Bitmap? = null

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
        mixText = c.text("", 12f, 0xFFFF6B6B.toInt(), 1).apply { setPadding(0, c.dp(4), 0, 0) }
        col.addView(title); col.addView(artist); col.addView(nextText); col.addView(mixText)
        vinyl = VinylView(c, m?.color ?: BRAND_RED).apply {
            reactive = true
            setOnClickListener { v -> v.haptic(true); kick(); PlayerHub.service?.djNext(true) }
        }
        col.addView(vinyl, lp(c.dp(196), c.dp(196)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = c.dp(22) })
        col.addView(c.text("Saltar", 14f, Color.WHITE, 2).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(6), 0, c.dp(30)) }, lp(MATCH, WRAP))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        loading = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0x99000000.toInt()); isClickable = true
        }
        loading.addView(VinylView(c, m?.color ?: BRAND_RED).also { loadingVinyl = it }, lp(c.dp(150), c.dp(150)))
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
        loadPlaylistCover(tracks)
        val t0 = SystemClock.uptimeMillis()
        // Lo que falte se analiza en segundo plano, mientras suena (y se suma a la mezcla en vivo)
        val need = tracks.filter { Analyzer.needs(it) }
        Analyzer.prioritize(c, (listOfNotNull(start?.takeIf { Analyzer.needs(it) }) + need.shuffled()).map { it.id }.distinct(), now = true)
        val ready = tracks.count { it.analyzed == 1 }
        if (ready < 2 && !(start != null && start.analyzed == 1 && ready >= 1)) {
            // Primerísima vez: basta con 2 canciones para empezar
            val first = (listOfNotNull(start?.takeIf { it.analyzed != 1 }) + tracks.filter { it.analyzed == 0 }.shuffled()).distinctBy { it.id }.take(2 - ready)
            first.forEachIndexed { i, t ->
                prog.text = "Escuchando tus canciones · ${i + 1} de ${first.size}"
                Analyzer.analyzeAndSave(c, t)
            }
        }
        val fresh = dao.tracksByIds(tracks.map { it.id }).associateBy { it.id }
        val list = tracks.mapNotNull { fresh[it.id] }
        val scores = DjBrain.learned(c, list.map { it.id }, mood)
        val m = mood
        if (m != null) {
            val good = list.count { DjBrain.score(it, m, scores[it.id] ?: 0f) >= 4f }
            val hint = DjBrain.genreHint(m)
            if (good < 8 && hint != null) Toast.makeText(c, "Tu DJ ${m.label} suena mejor con más $hint ${m.emoji}. Descárgala en la pestaña Descargar.", Toast.LENGTH_LONG).show()
        }
        // un instante para la animación (sin esperas largas)
        val waited = SystemClock.uptimeMillis() - t0
        if (waited < 1300) delay(1300 - waited)
        if (!isAdded) return
        PlayerHub.with(requireActivity()) { s ->
            s.startDj(list, m, start?.let { st -> list.firstOrNull { it.id == st.id } }, arguments?.getString("name") ?: "Modo DJ", scores,
                arguments?.getLong("pl", -4L) ?: -4L)
            loading.animate().alpha(0f).setDuration(400).withEndAction { loading.visibility = View.GONE }.start()
            askBatteryOnce()
        }
    }

    /** La portada de la playlist va en la etiqueta del disco. */
    private fun loadPlaylistCover(tracks: List<Track>) {
        val c = context ?: return
        val pl = arguments?.getLong("pl", -4L) ?: -4L
        viewLifecycleOwner.lifecycleScope.launch {
            val f = if (pl > 0) Library.dao(c).playlist(pl)?.coverPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                ?: tracks.firstOrNull { it.coverPath != null }?.let { Covers.fileFor(c, it) } else null
            val b = loadSmallBitmap(f) ?: return@launch
            playlistCover = b
            vinyl.cover = b; loadingVinyl?.cover = b
        }
    }

    /** Huawei y otras marcas cierran apps en segundo plano: pedir permiso una vez para que el DJ no se corte. */
    private fun askBatteryOnce() {
        val a = activity as? AppCompatActivity ?: return
        val p = Prefs2(a)
        if (p.djBatteryAsked || Device.ignoresBatteryOptimizations(a)) return
        p.djBatteryAsked = true
        val huawei = android.os.Build.MANUFACTURER.lowercase().let { it.contains("huawei") || it.contains("honor") }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
            .setTitle("Que la música no se corte")
            .setMessage("Para que el DJ siga sonando con la pantalla apagada, permite que DownSong funcione sin restricciones de batería." +
                if (huawei) "\n\nEn Huawei, además: Ajustes → Batería → Inicio de aplicaciones → DownSong → Gestionar manualmente (activa las 3 opciones)." else "")
            .setPositiveButton("Permitir") { _, _ ->
                SettingsActivity.requestBatteryExemption(a)
                if (huawei) a.window.decorView.postDelayed({ openHuaweiStartup(a) }, 1500)
            }
            .setNegativeButton("Ahora no", null).show()
    }

    private fun openHuaweiStartup(a: AppCompatActivity) {
        val comps = listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        for ((pkg, cls) in comps) {
            if (runCatching { a.startActivity(android.content.Intent().setComponent(android.content.ComponentName(pkg, cls))) }.isSuccess) return
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
            if (s != null) render()
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
        mixText.text = s.djMixLabel?.let { "🎚 $it" } ?: ""
        if (t.id != lastId) {
            lastId = t.id; (bg as? VizView)?.envelope = Analyzer.envelope(t)
            if (playlistCover == null) {
                val c = context ?: return
                viewLifecycleOwner.lifecycleScope.launch { loadSmallBitmap(Covers.fileFor(c, t))?.let { vinyl.cover = it } }
            }
        }
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
