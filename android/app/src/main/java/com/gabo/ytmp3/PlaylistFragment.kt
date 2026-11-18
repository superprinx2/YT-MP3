package com.gabo.ytmp3

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Una playlist al estilo Spotify. id = -1 → "Todas las canciones". */
class PlaylistFragment : Fragment(), AccentAware {

    companion object {
        fun new(id: Long) = PlaylistFragment().apply { arguments = Bundle().apply { putLong("id", id) } }
    }

    private val pid get() = requireArguments().getLong("id")
    private val isAll get() = pid < 0
    private val virtualName get() = when (pid) { -2L -> "Más escuchadas"; -3L -> "Archivos locales"; else -> "Todas las canciones" }
    private val act get() = requireActivity() as AppCompatActivity
    private var playlist: Playlist? = null
    private var tracks: List<Track> = emptyList()
    private lateinit var list: RecyclerView
    private lateinit var adapter: Adapter
    private var header: LinearLayout? = null
    private var headerColor = 0xFF3A1014.toInt()
    private var coverTarget: Any? = null   // Track o Playlist a la que se le cambia la portada
    private val playerListener: () -> Unit = { if (::adapter.isInitialized) { adapter.notifyDataSetChanged() } }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setCover(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val c = requireContext()
        list = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c); itemAnimator = null }
        list.setBackgroundColor(c.col(R.color.ios_bg))
        adapter = Adapter()
        list.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val dao = Library.dao(c)
                launch {
                    val pf: Flow<Playlist?> = if (isAll) flowOf(Playlist(id = pid, name = virtualName)) else dao.playlistFlow(pid)
                    pf.collect { p ->
                        if (p == null) { parentFragmentManager.popBackStack(); return@collect }
                        playlist = p; adapter.notifyItemChanged(0); loadHeaderColor()
                    }
                }
                (when (pid) { -1L -> dao.allTracks(); -2L -> dao.topTracks(); -3L -> dao.localTracks(); else -> dao.tracksOf(pid) }).collect { t ->
                    tracks = t; adapter.notifyDataSetChanged(); loadHeaderColor()
                }
            }
        }
        return list
    }

    override fun onStart() { super.onStart(); PlaybackService.addListener(playerListener) }
    override fun onStop() { PlaybackService.removeListener(playerListener); super.onStop() }
    override fun onAccent(color: Int) {}

    private fun coverFile(): File? {
        val c = context ?: return null
        playlist?.coverPath?.let(::File)?.takeIf { it.exists() }?.let { return it }
        return tracks.firstOrNull()?.let { Covers.fileFor(c, it) }
    }

    private fun loadHeaderColor() {
        viewLifecycleOwner.lifecycleScope.launch {
            headerColor = Art.color(coverFile())
            adapter.notifyItemChanged(0)
        }
    }

    // ------------------------------------------------------------------ acciones
    private fun play(index: Int, shuffle: Boolean = false) {
        val p = playlist ?: return
        if (tracks.isEmpty()) return
        val list = tracks
        PlayerHub.with(act) { s ->
            s.play(list, index, p.id, p.name, p.showVideo, shuffleStart = shuffle)
            if (!shuffle) (activity as? MainActivity)?.openPlayer()
        }
    }

    private fun isThisPlaying(): Boolean {
        val s = PlayerHub.service ?: return false
        return s.current() != null && s.playlistId == pid
    }

    private fun toggleVideo() {
        val p = playlist ?: return
        val v = !p.showVideo
        if (!isAll) lifecycleScope.launch { Library.dao(act).updatePlaylist(p.copy(showVideo = v)) }
        else { playlist = p.copy(showVideo = v); adapter.notifyItemChanged(0) }
        if (isThisPlaying()) PlayerHub.service?.setShowVideo(v)
        android.widget.Toast.makeText(act, if (v) "Videos visibles en esta playlist" else "Solo audio en esta playlist",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun songMenu(t: Track) {
        val opts = mutableListOf("Cambiar nombre", "Cambiar portada", "Agregar a otra playlist")
        if (!isAll) opts += "Quitar de esta playlist"
        opts += "Eliminar de la biblioteca"
        ActionSheet.show(act, t.title, opts, destructive = setOf(opts.size - 1)) { i ->
            when (opts[i]) {
                "Cambiar nombre" -> PlaylistPicker.askName(act, "Nombre de la canción", t.title, 120) { n -> updateTrack(t.copy(title = n)) }
                "Cambiar portada" -> { coverTarget = t; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                "Agregar a otra playlist" -> PlaylistPicker.show(act, "Agregar a playlist", emptySet(), null, "Agregar") { ids, _ ->
                    lifecycleScope.launch { Library.addToPlaylists(act, listOf(t.id), ids) }
                }
                "Quitar de esta playlist" -> lifecycleScope.launch { Library.dao(act).unlink(pid, t.id) }
                else -> ActionSheet.show(act, "¿Eliminar «${t.title}» de toda tu biblioteca? Se borra del celular.",
                    listOf("Eliminar"), destructive = setOf(0)) {
                    lifecycleScope.launch { Library.deleteTrack(act, t) }
                }
            }
        }
    }

    private fun updateTrack(t: Track) {
        lifecycleScope.launch {
            Library.dao(act).updateTrack(t)
            PlayerHub.service?.refreshTrack(t)
        }
    }

    private fun setCover(uri: Uri) {
        val target = coverTarget ?: return
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                val dst = File(Library.dir(act, "covers"), "custom_${System.currentTimeMillis()}.jpg")
                act.contentResolver.openInputStream(uri)?.use { i -> dst.outputStream().use { i.copyTo(it) } }
                dst
            }
            when (target) {
                is Track -> updateTrack(target.copy(coverPath = f.absolutePath))
                is Playlist -> Library.dao(act).updatePlaylist(target.copy(coverPath = f.absolutePath))
            }
        }
    }

    // ------------------------------------------------------------------ lista
    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = tracks.size + 2
        override fun getItemViewType(position: Int) = when (position) { 0 -> 0; tracks.size + 1 -> 2; else -> 1 }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val v = when (viewType) {
                0 -> LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }.also { header = it }
                1 -> c.listRow(ImageView(c), "", "", c.col(R.color.ios_label), c.col(R.color.ios_secondary), 50).apply {
                    addView(c.icon(R.drawable.ic_videocam, 18, c.col(R.color.ios_secondary)).apply { tag = "vid" })
                    addView(c.icon(R.drawable.ic_more_h, 40, c.col(R.color.ios_secondary), 9).apply { tag = "more" })
                    foreground = c.getDrawable(android.R.drawable.list_selector_background)
                }
                else -> View(c).apply { minimumHeight = c.dp(24) }
            }
            v.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (getItemViewType(position)) {
                0 -> bindHeader(h.itemView as LinearLayout)
                1 -> {
                    val t = tracks[position - 1]
                    val row = h.itemView as LinearLayout
                    val c = row.context
                    (row.getChildAt(0) as ImageView).cover(t, 4f)
                    val cur = PlayerHub.service?.current()?.id == t.id
                    row.findViewWithTag<TextView>("title").apply { text = t.title; setTextColor(if (cur) BRAND_RED else c.col(R.color.ios_label)) }
                    row.findViewWithTag<TextView>("sub").text = listOfNotNull(t.artist.ifEmpty { null },
                        Util.fmtDuration(t.durationMs / 1000.0).ifEmpty { null }).joinToString(" · ")
                    row.findViewWithTag<View>("vid").visibility = if (t.videoPath != null) View.VISIBLE else View.GONE
                    row.findViewWithTag<View>("more").setOnClickListener { it.haptic(); songMenu(t) }
                    row.setOnClickListener { it.haptic(); play(position - 1) }
                    row.setOnLongClickListener { it.haptic(); songMenu(t); true }
                }
            }
        }
    }

    private fun bindHeader(box: LinearLayout) {
        val c = box.context
        val p = playlist ?: return
        box.removeAllViews()
        box.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(headerColor, darker(headerColor, 0.55f), c.col(R.color.ios_bg)))
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(c.dp(6), c.dp(10), c.dp(6), 0) }
        top.addView(c.icon(R.drawable.ic_expand, 44, Color.WHITE, 8).apply {
            rotation = 90f; setOnClickListener { it.haptic(); parentFragmentManager.popBackStack() }
        })
        box.addView(top)
        val img = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = roundRect(0xFF222222.toInt(), c.dp(6).toFloat()); elevation = c.dp(12).toFloat()
        }
        box.addView(img, lp(c.dp(210), c.dp(210)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = c.dp(4) })
        coverFile()?.let { img.load(it) }
        box.addView(c.text(p.name, 24f, Color.WHITE, 2, 2).apply { setPadding(c.dp(18), c.dp(18), c.dp(18), 0) })
        val totalMin = tracks.sumOf { it.durationMs } / 60000
        val dur = if (totalMin >= 60) "${totalMin / 60} h ${totalMin % 60} min" else "$totalMin min"
        box.addView(c.text("${tracks.size} ${if (tracks.size == 1) "canción" else "canciones"} · $dur", 13f, 0xCCFFFFFF.toInt())
            .apply { setPadding(c.dp(18), c.dp(4), c.dp(18), 0) })

        val actions = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(10), c.dp(8), c.dp(16), c.dp(10))
        }
        val hasVideos = tracks.any { it.videoPath != null }
        if (hasVideos) actions.addView(c.text(if (p.showVideo) "🎬 Video: sí" else "🎬 Video: no", 13f, Color.WHITE, 1).apply {
            gravity = Gravity.CENTER; minHeight = c.dp(32); setPadding(c.dp(12), 0, c.dp(12), 0)
            background = GradientDrawable().apply {
                cornerRadius = c.dp(16).toFloat()
                if (p.showVideo) setColor(BRAND_RED) else setStroke(c.dp(1), 0x99FFFFFF.toInt())
            }
            setOnClickListener { it.haptic(); toggleVideo() }
        }, lp(WRAP, WRAP).apply { marginStart = c.dp(8); marginEnd = c.dp(4) })
        if (!isAll) actions.addView(c.icon(R.drawable.ic_add, 44, Color.WHITE, 9).apply {
            setOnClickListener { it.haptic(); ImportSheet.start(act, pid) }
        })
        if (!isAll) actions.addView(c.icon(R.drawable.ic_more_h, 44, Color.WHITE, 9).apply {
            setOnClickListener {
                it.haptic()
                PlaylistMenu.show(act, p.id, p.name, pickCover = {
                    coverTarget = p; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { parentFragmentManager.popBackStack() }
            }
        })
        actions.addView(View(c), lp(0, 1, 1f))
        val shuffleOn = Prefs2(c).shuffle
        actions.addView(c.icon(R.drawable.ic_shuffle, 44, if (shuffleOn) BRAND_RED else Color.WHITE, 9).apply {
            setOnClickListener {
                it.haptic()
                val on = !Prefs2(c).shuffle
                Prefs2(c).shuffle = on
                PlayerHub.service?.setShuffle(on)
                setColorFilter(if (on) BRAND_RED else Color.WHITE)
            }
        })
        val playing = isThisPlaying() && PlayerHub.service?.active?.isPlaying == true
        actions.addView(c.icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, 56, Color.BLACK, 14).apply {
            background = oval(BRAND_RED); setColorFilter(Color.WHITE)
            setOnClickListener {
                it.haptic(true)
                if (isThisPlaying()) PlayerHub.service?.togglePlay()
                else play(0, shuffle = Prefs2(c).shuffle)
                list.postDelayed({ adapter.notifyItemChanged(0) }, 300)
            }
        }, lp(c.dp(56), c.dp(56)).apply { marginStart = c.dp(8) })
        box.addView(actions)
        if (tracks.isEmpty()) box.addView(c.text(if (pid == -2L) "Escucha música y tus canciones más escuchadas aparecerán aquí." else if (pid == -3L) "Agrega música del celular desde Ajustes › Biblioteca y almacenamiento." else if (isAll) "Aún no tienes canciones." else
            "Esta playlist está vacía. Toca ＋ para agregar música del celular, o descarga canciones y elige esta playlist.",
            14f, c.col(R.color.ios_secondary), 0, 0).apply { gravity = Gravity.CENTER; setPadding(c.dp(32), c.dp(24), c.dp(32), c.dp(24)) })
    }
}
