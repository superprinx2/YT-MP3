package com.gabo.ytmp3

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** Inicio: Más escuchadas, Archivos locales, Modo DJ y lo que escuchaste hace poco. */
class HomeFragment : Fragment() {

    private lateinit var avatar: ImageView
    private lateinit var topSub: TextView
    private lateinit var localSub: TextView
    private lateinit var djSub: TextView
    /** Fondo animado de las tarjetas del DJ: el visualizador nuevo (o el caleidoscopio en modo ahorro). */
    private lateinit var djBg: View
    private fun djBgRun(on: Boolean) { (djBg as? VizView)?.let { if (on) it.start() else it.stop() }; (djBg as? KaleidoView)?.running = on }
    private lateinit var stats: LinearLayout
    private lateinit var statsCover: ImageView
    private lateinit var statsTitle: TextView
    private lateinit var statsSub: TextView
    private lateinit var recentRow: LinearLayout
    private lateinit var recentTitle: TextView
    private var all: List<Track> = emptyList()
    private var recent: List<Track> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val c = requireContext()
        val scroll = ScrollView(c)
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(24)) }
        scroll.addView(col)

        val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        avatar = ImageView(c).apply { setOnClickListener { it.haptic(); settings("profile") } }
        bar.addView(avatar, lp(c.dp(34), c.dp(34)))
        bar.addView(c.text("Inicio", 24f, c.col(R.color.ios_label), 2).apply { setPadding(c.dp(12), 0, 0, 0) }, lp(0, WRAP, 1f))
        bar.addView(c.icon(R.drawable.ic_gear, 44, c.col(R.color.ios_label), 10).apply { setOnClickListener { it.haptic(); settings(null) } })
        col.addView(bar, lp(MATCH, WRAP).apply { bottomMargin = c.dp(14) })

        val grid = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val top = card("🔥", "Más escuchadas", intArrayOf(0xFFFF5A5F.toInt(), 0xFF8E0E1C.toInt())) { (activity as? MainActivity)?.openPlaylist(-2) }
        topSub = top.findViewWithTag("sub")
        val local = card("📁", "Archivos locales", intArrayOf(0xFF3A7BD5.toInt(), 0xFF1B2A4A.toInt())) { (activity as? MainActivity)?.openPlaylist(-3) }
        localSub = local.findViewWithTag("sub")
        grid.addView(top, lp(0, c.dp(150), 1f).apply { marginEnd = c.dp(6) })
        grid.addView(local, lp(0, c.dp(150), 1f).apply { marginStart = c.dp(6) })
        col.addView(grid)

        // Modo DJ: aleatorio total o por estado de ánimo
        val djRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun djCard(title: String, sub: String, tint: Int, onClick: () -> Unit) = FrameLayout(c).apply {
            clipToOutline = true; background = roundRect(Color.TRANSPARENT, c.dp(14).toFloat())
            foreground = c.getDrawable(android.R.drawable.list_selector_background)
            addView(View(c).apply { setBackgroundColor(0x77000000) }, FrameLayout.LayoutParams(MATCH, MATCH))
            val inner = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(14)) }
            inner.addView(VinylView(c, tint), lp(c.dp(54), c.dp(54)))
            inner.addView(View(c), lp(1, 0, 1f))
            inner.addView(c.text(title, 18f, Color.WHITE, 2, 2))
            inner.addView(c.text(sub, 12f, 0xDDFFFFFF.toInt(), 0, 2))
            addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { it.haptic(true); onClick() }
        }
        val dj1 = djCard("🎧 Modo DJ", "Aleatorio total, directo", BRAND_RED) { startDj() }
        val dj2 = djCard("🎧 Por estado de ánimo", "¿Cómo te sientes hoy?", 0xFFB14DFF.toInt()) { (activity as? MainActivity)?.openMoods() }
        djRow.addView(dj1, lp(0, c.dp(170), 1f).apply { marginEnd = c.dp(6) })
        djRow.addView(dj2, lp(0, c.dp(170), 1f).apply { marginStart = c.dp(6) })
        // Un solo visualizador detrás de las dos tarjetas; una máscara del color de la página
        // dibuja las esquinas redondeadas y el espacio entre ellas.
        val djBox = FrameLayout(c)
        djBg = VizView(c).apply { djStyle = true }
        djBox.addView(djBg, FrameLayout.LayoutParams(MATCH, MATCH))
        djBox.addView(djRow, FrameLayout.LayoutParams(MATCH, WRAP))
        val pageBg = c.col(R.color.ios_bg)
        val mask = object : View(c) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = pageBg }
            private val path = android.graphics.Path()
            override fun onDraw(cv: android.graphics.Canvas) {
                path.reset(); path.fillType = android.graphics.Path.FillType.EVEN_ODD
                path.addRect(0f, 0f, width.toFloat(), height.toFloat(), android.graphics.Path.Direction.CW)
                val r = c.dp(14).toFloat()
                for (v in listOf(dj1, dj2)) path.addRoundRect(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), r, r, android.graphics.Path.Direction.CW)
                cv.drawPath(path, paint)
            }
        }
        djBox.addView(mask, FrameLayout.LayoutParams(MATCH, MATCH))
        djRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> mask.invalidate() }
        col.addView(djBox, lp(MATCH, WRAP).apply { topMargin = c.dp(12) })
        djSub = c.text("", 1f, Color.TRANSPARENT)

        // Tus estadísticas
        stats = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
            background = roundRect(c.col(R.color.ios_card), c.dp(14).toFloat())
            foreground = c.getDrawable(android.R.drawable.list_selector_background)
            setOnClickListener { it.haptic(); showStats() }
        }
        statsCover = ImageView(c)
        stats.addView(statsCover, lp(c.dp(64), c.dp(64)))
        val st = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(12), 0, 0, 0) }
        st.addView(c.text("📊 Tu canción del mes", 12f, c.col(R.color.ios_secondary), 1))
        statsTitle = c.text("Escucha música y aquí verás tus estadísticas", 16f, c.col(R.color.ios_label), 2, 2)
        statsSub = c.text("", 13f, c.col(R.color.ios_secondary))
        st.addView(statsTitle); st.addView(statsSub)
        stats.addView(st, lp(0, WRAP, 1f))
        col.addView(stats, lp(MATCH, WRAP).apply { topMargin = c.dp(12) })

        recentTitle = c.text("Escuchado recientemente", 20f, c.col(R.color.ios_label), 2).apply { setPadding(0, c.dp(24), 0, c.dp(10)) }
        col.addView(recentTitle)
        recentRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(recentRow) })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val dao = Library.dao(c)
                launch { dao.topTracks().collect { topSub.text = if (it.isEmpty()) "Escucha música y aparecerá aquí" else "${it.size} canciones" } }
                launch { dao.localTracks().collect { localSub.text = "${it.size} canciones del celular" } }
                launch { dao.allTracks().collect { all = it; djSub.text = "Canciones al azar de tus ${it.size} canciones" } }
                dao.recentTracks().collect { recent = it; bindRecent() }
            }
        }
        (activity as? MainActivity)?.onBottomInset { b -> col.setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(24) + b) }
        return scroll
    }

    override fun onResume() {
        super.onResume()
        avatar.setImageBitmap(Covers.avatarBitmap(requireContext()))
        djBgRun(!isHidden)
        loadStats()
    }

    override fun onPause() { djBgRun(false); super.onPause() }

    override fun onHiddenChanged(hidden: Boolean) { djBgRun(!hidden); if (!hidden) loadStats() }

    private fun settings(section: String?) =
        startActivity(Intent(requireContext(), SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, section))

    private fun card(emoji: String, title: String, colors: IntArray, onClick: () -> Unit): LinearLayout {
        val c = requireContext()
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(c.dp(16), c.dp(14), c.dp(14), c.dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = c.dp(14).toFloat() }
            foreground = c.getDrawable(android.R.drawable.list_selector_background)
            addView(c.text(emoji, 30f, Color.WHITE))
            addView(View(c), lp(1, 0, 1f))
            addView(c.text(title, 18f, Color.WHITE, 2, 2))
            addView(c.text("", 12f, 0xDDFFFFFF.toInt(), 0, 2).apply { tag = "sub" })
            setOnClickListener { it.haptic(); onClick() }
        }
    }

    private fun bindRecent() {
        val c = context ?: return
        recentRow.removeAllViews()
        recentTitle.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        recent.forEachIndexed { i, t ->
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { v ->
                    v.haptic()
                    val list = recent
                    PlayerHub.with(requireActivity()) { s -> s.play(list, i, null, "Escuchado recientemente", false); (activity as? MainActivity)?.openPlayer() }
                }
            }
            box.addView(ImageView(c).apply { cover(t, 8f) }, lp(c.dp(128), c.dp(128)))
            box.addView(c.text(t.title, 13f, c.col(R.color.ios_label), 1, 2).apply { setPadding(0, c.dp(6), 0, 0) }, lp(c.dp(128), WRAP))
            recentRow.addView(box, lp(WRAP, WRAP).apply { marginEnd = c.dp(12) })
        }
    }

    private fun startDj() {
        if (all.size < 2) { Toast.makeText(requireContext(), "Necesitas al menos 2 canciones", Toast.LENGTH_SHORT).show(); return }
        (activity as? MainActivity)?.openDj(null, null, null, "Aleatorio total")
    }

    private fun monthStart(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    }.timeInMillis

    private fun loadStats() {
        val c = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = Library.dao(c)
            val since = monthStart()
            val top = dao.topSince(since, 1).firstOrNull() ?: return@launch
            val t = dao.track(top.trackId) ?: return@launch
            val hours = dao.msSince(since) / 3_600_000.0
            statsCover.cover(t, 8f)
            statsTitle.text = t.title
            statsSub.text = "${top.n} veces · ${String.format(java.util.Locale.US, "%.1f", hours)} h escuchadas este mes"
        }
    }

    private fun showStats() {
        val a = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = Library.dao(a)
            val rows = dao.topSince(monthStart(), 10)
            if (rows.isEmpty()) return@launch
            val map = dao.tracksByIds(rows.map { it.trackId }).associateBy { it.id }
            val list = rows.mapNotNull { r -> map[r.trackId]?.let { it to r.n } }
            ActionSheet.show(a, "Tus más escuchadas del mes", list.mapIndexed { i, (t, n) -> "${i + 1}. ${t.title} · $n" }) { i ->
                val tracks = list.map { it.first }
                PlayerHub.with(a) { s -> s.play(tracks, i, null, "Tus más escuchadas", false); (activity as? MainActivity)?.openPlayer() }
            }
        }
    }
}
