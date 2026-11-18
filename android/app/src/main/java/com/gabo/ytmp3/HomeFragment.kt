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
    private lateinit var djKaleido: KaleidoView
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

        // Modo DJ con el caleidoscopio de fondo
        val dj = FrameLayout(c).apply {
            clipToOutline = true; background = roundRect(Color.BLACK, c.dp(14).toFloat())
            foreground = c.getDrawable(android.R.drawable.list_selector_background)
            setOnClickListener { it.haptic(true); startDj() }
        }
        djKaleido = KaleidoView(c).apply { seed = 4242 }
        dj.addView(djKaleido, FrameLayout.LayoutParams(MATCH, MATCH))
        dj.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xCC000000.toInt(), 0x22000000)) },
            FrameLayout.LayoutParams(MATCH, MATCH))
        val djText = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(18), c.dp(16), c.dp(18), c.dp(16)) }
        djText.addView(c.text("🎧 Modo DJ", 24f, Color.WHITE, 2))
        djSub = c.text("Canciones al azar de toda tu biblioteca", 14f, 0xDDFFFFFF.toInt(), 0, 2)
        djText.addView(djSub)
        djText.addView(c.text("▶  Empezar", 15f, Color.WHITE, 2).apply {
            gravity = Gravity.CENTER; minHeight = c.dp(38); setPadding(c.dp(18), 0, c.dp(18), 0)
            background = roundRect(BRAND_RED, c.dp(19).toFloat())
        }, lp(WRAP, WRAP).apply { topMargin = c.dp(12) })
        dj.addView(djText, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        col.addView(dj, lp(MATCH, c.dp(170)).apply { topMargin = c.dp(12) })

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
        return scroll
    }

    override fun onResume() {
        super.onResume()
        avatar.setImageBitmap(Covers.avatarBitmap(requireContext()))
        djKaleido.running = true
    }

    override fun onPause() { djKaleido.running = false; super.onPause() }

    override fun onHiddenChanged(hidden: Boolean) { djKaleido.running = !hidden }

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
        if (all.isEmpty()) { Toast.makeText(requireContext(), "Tu biblioteca está vacía", Toast.LENGTH_SHORT).show(); return }
        val list = all.shuffled()
        PlayerHub.with(requireActivity()) { s ->
            s.setShuffle(true)
            s.play(list, 0, -4, "Modo DJ", false, shuffleStart = true)
            (activity as? MainActivity)?.openPlayer()
        }
    }
}
