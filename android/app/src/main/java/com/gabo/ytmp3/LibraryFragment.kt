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
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Biblioteca al estilo Spotify: tus playlists, con su portada. */
class LibraryFragment : Fragment() {

    private sealed class Row {
        data class All(val count: Int) : Row()
        data class Pl(val p: PlaylistRow) : Row()
        object Empty : Row()
        object Footer : Row()
    }

    private val rows = ArrayList<Row>()
    private lateinit var adapter: Adapter
    private lateinit var avatar: ImageView
    private val act get() = requireActivity() as AppCompatActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val c = requireContext()
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        // barra superior
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(16), c.dp(14), c.dp(8), c.dp(8))
        }
        avatar = ImageView(c).apply { setOnClickListener { it.haptic(); openSettings("profile") } }
        bar.addView(avatar, lp(c.dp(34), c.dp(34)))
        bar.addView(c.text("Tu biblioteca", 24f, c.col(R.color.ios_label), 2).apply { setPadding(c.dp(12), 0, 0, 0) }, lp(0, WRAP, 1f))
        bar.addView(c.icon(R.drawable.ic_add, 44, c.col(R.color.ios_label), 9).apply {
            setOnClickListener { it.haptic(); newPlaylist() }
        })
        bar.addView(c.icon(R.drawable.ic_gear, 44, c.col(R.color.ios_label), 10).apply {
            setOnClickListener { it.haptic(); openSettings(null) }
        })
        root.addView(bar)

        val list = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c); itemAnimator = null }
        adapter = Adapter()
        list.adapter = adapter
        root.addView(list, lp(MATCH, 0, 1f))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val dao = Library.dao(c)
                dao.playlistRows().combine(dao.trackCount()) { a, b -> a to b }.collect { (pls, count) ->
                    rows.clear()
                    if (count > 0 || pls.isNotEmpty()) rows += Row.All(count)
                    pls.forEach { rows += Row.Pl(it) }
                    if (pls.isEmpty()) rows += Row.Empty
                    rows += Row.Footer
                    adapter.notifyDataSetChanged()
                }
            }
        }
        return root
    }

    override fun onResume() {
        super.onResume()
        avatar.setImageBitmap(Covers.avatarBitmap(requireContext()))
    }

    private fun openSettings(section: String?) =
        startActivity(Intent(requireContext(), SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, section))

    private fun newPlaylist() = PlaylistPicker.askName(act, "Nueva playlist", "") { name ->
        lifecycleScope.launch { val id = Library.createPlaylist(act, name); (activity as? MainActivity)?.openPlaylist(id) }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.All -> 0; is Row.Pl -> 1; Row.Empty -> 2; Row.Footer -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val v: View = when (viewType) {
                0, 1 -> c.listRow(ImageView(c), "", "", c.col(R.color.ios_label), c.col(R.color.ios_secondary), 60).apply {
                    background = c.getDrawable(R.drawable.bg_row_single)?.mutate()?.also { it.alpha = 0 }
                    isClickable = true
                    foreground = c.getDrawable(android.R.drawable.list_selector_background)
                }
                2 -> emptyView(c)
                else -> footerView(c)
            }
            v.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val c = h.itemView.context
            when (val r = rows[position]) {
                is Row.All -> {
                    val row = h.itemView as LinearLayout
                    val img = row.getChildAt(0) as ImageView
                    img.setImageResource(R.drawable.ic_library)
                    img.setColorFilter(Color.WHITE)
                    img.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    img.setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(14))
                    img.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xFFFF5A5F.toInt(), 0xFF8E0E1C.toInt())).apply { cornerRadius = c.dp(6).toFloat() }
                    row.findViewWithTag<android.widget.TextView>("title").text = "Todas las canciones"
                    row.findViewWithTag<android.widget.TextView>("sub").text = "Biblioteca · ${r.count} canciones"
                    row.setOnClickListener { it.haptic(); (activity as? MainActivity)?.openPlaylist(-1) }
                    row.setOnLongClickListener(null)
                }
                is Row.Pl -> {
                    val row = h.itemView as LinearLayout
                    val img = row.getChildAt(0) as ImageView
                    img.clearColorFilter(); img.setPadding(0, 0, 0, 0)
                    img.scaleType = ImageView.ScaleType.CENTER_CROP
                    img.clipToOutline = true
                    img.background = roundRect(0xFF222222.toInt(), c.dp(6).toFloat())
                    img.setImageDrawable(null)
                    viewLifecycleOwner.lifecycleScope.launch { img.load(Art.playlistCover(c, r.p)) { crossfade(true) } }
                    row.findViewWithTag<android.widget.TextView>("title").text = r.p.name
                    row.findViewWithTag<android.widget.TextView>("sub").text =
                        "Playlist · ${r.p.count} ${if (r.p.count == 1) "canción" else "canciones"}"
                    row.setOnClickListener { it.haptic(); (activity as? MainActivity)?.openPlaylist(r.p.id) }
                    row.setOnLongClickListener { it.haptic(); PlaylistMenu.show(act, r.p.id, r.p.name) {}; true }
                }
                else -> {}
            }
        }
    }

    private fun emptyView(c: android.content.Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(c.dp(32), c.dp(48), c.dp(32), c.dp(24))
        addView(ImageView(c).apply { setImageResource(R.drawable.logo_downsong) }, lp(c.dp(88), c.dp(88)))
        addView(c.text("Tu biblioteca está vacía", 20f, c.col(R.color.ios_label), 2).apply {
            gravity = Gravity.CENTER; setPadding(0, c.dp(16), 0, c.dp(6))
        })
        addView(c.text("Descarga canciones y videos de YouTube, Spotify, TikTok, Instagram o Facebook, " +
            "o agrega la música que ya tienes en el celular.", 14f, c.col(R.color.ios_secondary), 0).apply { gravity = Gravity.CENTER })
        addView(c.text("Descargar música", 16f, Color.WHITE, 1).apply {
            gravity = Gravity.CENTER; minHeight = c.dp(48)
            background = roundRect(BRAND_RED, c.dp(24).toFloat()); setPadding(c.dp(24), 0, c.dp(24), 0)
            pressable(); setOnClickListener { it.haptic(); (activity as? MainActivity)?.selectTab(1) }
        }, lp(WRAP, WRAP).apply { topMargin = c.dp(20) })
        addView(c.text("Agregar música del celular", 16f, c.col(R.color.ios_label), 1).apply {
            gravity = Gravity.CENTER; minHeight = c.dp(48)
            background = GradientDrawable().apply { cornerRadius = c.dp(24).toFloat(); setStroke(c.dp(1), c.col(R.color.ios_secondary)) }
            setPadding(c.dp(24), 0, c.dp(24), 0)
            pressable(); setOnClickListener { it.haptic(); ImportSheet.start(act, null) }
        }, lp(WRAP, WRAP).apply { topMargin = c.dp(12) })
    }

    private fun footerView(c: android.content.Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, c.dp(36), 0, c.dp(20))
        addView(c.text("Desarrollado por", 12f, c.col(R.color.ios_secondary)).apply { gravity = Gravity.CENTER })
        addView(c.text("Gabo Matica · @GabLuciani", 13f, c.col(R.color.ios_secondary), 1).apply {
            setPadding(c.dp(6), c.dp(4), c.dp(6), c.dp(4)); gravity = Gravity.CENTER
            setOnClickListener { SettingsActivity.copyHandle(act, it) }
        })
        addView(c.text("🍔 Tengo hambre · Dona", 15f, Color.WHITE, 1).apply {
            gravity = Gravity.CENTER; minHeight = c.dp(44)
            setBackgroundResource(R.drawable.bg_chip_ultra); setPadding(c.dp(20), 0, c.dp(20), 0)
            pressable(); setOnClickListener { it.haptic(); Donate.show(act) }
        }, lp(WRAP, WRAP).apply { topMargin = c.dp(12) })
    }
}

/** Menú de una playlist: renombrar, portada, borrar. */
object PlaylistMenu {
    fun show(a: AppCompatActivity, id: Long, name: String, pickCover: (() -> Unit)? = null, onDeleted: () -> Unit) {
        val opts = mutableListOf("Cambiar nombre")
        if (pickCover != null) opts += "Cambiar portada"
        opts += "Eliminar playlist"
        ActionSheet.show(a, name, opts, destructive = setOf(opts.size - 1)) { i ->
            when (opts[i]) {
                "Cambiar nombre" -> PlaylistPicker.askName(a, "Nombre de la playlist", name) { n ->
                    a.lifecycleScope.launch { Library.dao(a).playlist(id)?.let { Library.dao(a).updatePlaylist(it.copy(name = n)) } }
                }
                "Cambiar portada" -> pickCover?.invoke()
                else -> ActionSheet.show(a, "¿Eliminar «$name»? Las canciones siguen en tu biblioteca.",
                    listOf("Eliminar playlist"), destructive = setOf(0)) {
                    a.lifecycleScope.launch {
                        Library.dao(a).clearPlaylist(id); Library.dao(a).deletePlaylist(id); onDeleted()
                    }
                }
            }
        }
    }
}

@Suppress("unused") private fun FrameLayout.noop() = Unit
