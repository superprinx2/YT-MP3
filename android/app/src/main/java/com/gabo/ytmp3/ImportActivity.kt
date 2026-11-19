package com.gabo.ytmp3

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Agregar música del celular: carpetas, canciones y artistas, al estilo de la app. */
class ImportActivity : AppCompatActivity() {
    companion object {
        fun intent(c: Context, playlistId: Long?) = Intent(c, ImportActivity::class.java).putExtra("pl", playlistId ?: -1L)
    }

    private val plId get() = intent.getLongExtra("pl", -1L).takeIf { it >= 0 }
    private var songs: List<Library.LocalSong> = emptyList()
    private val selected = LinkedHashSet<Long>()
    private var already: Set<Long> = emptySet()
    private var tab = 0               // 0 carpetas, 1 canciones, 2 artistas
    private var folder: String? = null
    private var artist: String? = null
    private var query = ""
    private lateinit var list: RecyclerView
    private lateinit var addBtn: TextView
    private lateinit var allBtn: TextView
    private lateinit var crumb: TextView
    private lateinit var tabs: List<TextView>
    private val thumbs = LruCache<Long, Bitmap>(200)
    private var plName = "tu biblioteca"

    private sealed class Item {
        class Folder(val name: String, val songs: List<Library.LocalSong>) : Item()
        class Song(val s: Library.LocalSong) : Item()
        class Artist(val name: String, val count: Int) : Item()
    }
    private var items: List<Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        val c = this
        val root = FrameLayout(c).apply { setBackgroundColor(0xFF0B0B0D.toInt()) }
        val bg = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.55f }
        if (Build.VERSION.SDK_INT >= 31) bg.setRenderEffect(RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP))
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, 0xCC0B0B0D.toInt(), 0xF00B0B0D.toInt())) },
            FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(16), 0) }
        top.addView(text("✕", 22f, Color.WHITE, 2).apply { gravity = Gravity.CENTER; setOnClickListener { finish() } }, lp(dp(48), dp(48)))
        val tt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        tt.addView(text("Agregar música", 22f, Color.WHITE, 2))
        val sub = text("del celular", 13f, 0xBBFFFFFF.toInt())
        tt.addView(sub)
        top.addView(tt, lp(0, WRAP, 1f))
        col.addView(top)

        val search = EditText(c).apply {
            hint = "🔍  Buscar canción, artista o carpeta"; setHintTextColor(0x88FFFFFF.toInt()); setTextColor(Color.WHITE)
            textSize = 15f; isSingleLine = true
            background = roundRect(0x26FFFFFF, dp(14).toFloat()); setPadding(dp(14), dp(11), dp(14), dp(11))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim().lowercase(); rebuild() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            })
        }
        col.addView(search, lp(MATCH, WRAP).apply { setMargins(dp(16), dp(10), dp(16), dp(8)) })

        val tabRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(4)) }
        tabs = listOf("📁 Carpetas", "🎵 Canciones", "🎤 Artistas").mapIndexed { i, l ->
            text(l, 14f, Color.WHITE, 1).apply {
                gravity = Gravity.CENTER; minHeight = dp(36); setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { it.haptic(); tab = i; folder = null; artist = null; rebuild() }
            }.also { tabRow.addView(it, lp(WRAP, WRAP).apply { marginEnd = dp(8) }) }
        }
        col.addView(tabRow)
        crumb = text("", 15f, BRAND_RED, 1).apply {
            setPadding(dp(18), dp(6), dp(18), dp(6)); visibility = View.GONE
            setOnClickListener { it.haptic(); folder = null; artist = null; rebuild() }
        }
        col.addView(crumb)

        list = RecyclerView(c).apply {
            clipToPadding = false; setPadding(dp(10), 0, dp(10), dp(120))
            layoutAnimation = LayoutAnimationController(AnimationUtils.loadAnimation(c, R.anim.item_enter)).apply { delay = 0.06f }
        }
        list.adapter = Adapter()
        col.addView(list, lp(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // barra inferior fija
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF0B0B0D.toInt(), 0xE60B0B0D.toInt(), 0x000B0B0D))
        }
        allBtn = text("Seleccionar todo", 14f, Color.WHITE, 1).apply { setPadding(dp(4), dp(10), dp(12), dp(10)); setOnClickListener { it.haptic(); toggleAll() } }
        bar.addView(allBtn)
        addBtn = text("Agregar", 16f, Color.WHITE, 2).apply {
            gravity = Gravity.CENTER; minHeight = dp(52)
            background = roundRect(BRAND_RED, dp(26).toFloat()); pressable()
            setOnClickListener { it.haptic(true); add(songs.filter { s -> s.id in selected }) }
        }
        bar.addView(addBtn, lp(0, WRAP, 1f))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            col.setPadding(0, b.top, 0, 0); bar.setPadding(dp(16), dp(12), dp(16), dp(16) + b.bottom); ins
        }

        lifecycleScope.launch {
            val dao = Library.dao(c)
            plId?.let { id ->
                dao.playlist(id)?.let { p -> plName = "«${p.name}»" }
                already = dao.tracksOfNow(id).mapNotNull { it.key.removePrefix("local:").takeIf { k -> it.key.startsWith("local:") }?.toLongOrNull() }.toSet()
                dao.tracksOfNow(id).firstOrNull()?.let { t -> bg.load(Covers.fileFor(c, t)) }
            }
            sub.text = "a $plName"
            songs = withContext(Dispatchers.IO) { Library.scanDevice(c) }
            if (songs.isEmpty()) Toast.makeText(c, "No encontramos música en el celular", Toast.LENGTH_LONG).show()
            rebuild()
        }
    }

    private fun matches(s: Library.LocalSong) = query.isEmpty() || s.title.lowercase().contains(query) ||
        s.artist.lowercase().contains(query) || s.folder.lowercase().contains(query)

    private fun visibleSongs(): List<Library.LocalSong> = songs.filter { matches(it) }
        .filter { folder == null || it.folder == folder }.filter { artist == null || it.artist.ifEmpty { "Desconocido" } == artist }

    private fun rebuild() {
        tabs.forEachIndexed { i, v -> v.background = if (i == tab) roundRect(0x33FFFFFF, dp(18).toFloat()) else null; v.alpha = if (i == tab) 1f else 0.6f }
        crumb.visibility = if (folder != null || artist != null) View.VISIBLE else View.GONE
        crumb.text = if (folder != null) "‹ Carpetas  ·  $folder" else if (artist != null) "‹ Artistas  ·  $artist" else ""
        items = when {
            folder != null || artist != null || tab == 1 -> visibleSongs().map { Item.Song(it) }
            tab == 0 -> songs.filter { matches(it) }.groupBy { it.folder }.toList().sortedByDescending { it.second.size }.map { Item.Folder(it.first, it.second) }
            else -> songs.filter { matches(it) }.groupBy { it.artist.ifEmpty { "Desconocido" } }.toList().sortedByDescending { it.second.size }.map { Item.Artist(it.first, it.second.size) }
        }
        val grid = items.firstOrNull() is Item.Folder
        list.layoutManager = GridLayoutManager(this, if (grid) 2 else 1)
        list.adapter?.notifyDataSetChanged()
        list.scheduleLayoutAnimation()
        refreshBar()
    }

    private fun refreshBar() {
        val n = selected.size
        addBtn.text = if (n == 0) "Elige canciones" else "Agregar $n ${if (n == 1) "canción" else "canciones"}"
        addBtn.isEnabled = n > 0; addBtn.alpha = if (n > 0) 1f else 0.45f
        allBtn.visibility = if (items.firstOrNull() is Item.Song) View.VISIBLE else View.GONE
        val vis = visibleSongs().filter { it.id !in already }
        allBtn.text = if (vis.isNotEmpty() && vis.all { it.id in selected }) "Quitar todo" else "Seleccionar todo"
    }

    private fun bumpCounter() { addBtn.scaleX = 0.92f; addBtn.scaleY = 0.92f; addBtn.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start() }

    private fun toggleAll() {
        val vis = visibleSongs().filter { it.id !in already }
        if (vis.all { it.id in selected }) vis.forEach { selected -= it.id } else vis.forEach { selected += it.id }
        list.adapter?.notifyDataSetChanged(); refreshBar(); bumpCounter()
    }

    private fun add(pick: List<Library.LocalSong>) {
        if (pick.isEmpty()) return
        val go = { ids: Set<Long> ->
            lifecycleScope.launch {
                addBtn.text = "Agregando…"
                val tids = withContext(Dispatchers.IO) { Library.importLocal(this@ImportActivity, pick) }
                Library.addToPlaylists(this@ImportActivity, tids, ids)
                addBtn.text = "✓ ${tids.size} agregadas"
                addBtn.animate().scaleX(1.06f).scaleY(1.06f).setDuration(160).withEndAction {
                    addBtn.animate().scaleX(1f).scaleY(1f).setDuration(160).withEndAction { finish() }.start()
                }.start()
            }
        }
        val id = plId
        if (id != null) go(setOf(id))
        else PlaylistPicker.show(this, "¿En qué playlist?", emptySet(), folder ?: "Mi música", "Agregar") { ids, _ -> go(ids) }
    }

    private fun thumb(iv: ImageView, s: Library.LocalSong) {
        iv.setImageDrawable(null); iv.tag = s.id
        thumbs.get(s.id)?.let { iv.setImageBitmap(it); return }
        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(s.uri, Size(200, 200), null)
                    else contentResolver.openInputStream(android.net.Uri.parse("content://media/external/audio/albumart/${s.albumId}"))
                        ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (b != null) { thumbs.put(s.id, b); if (iv.tag == s.id) iv.setImageBitmap(b) }
            else if (iv.tag == s.id) iv.setImageBitmap(KaleidoRenderer.bitmap(s.id, 96))
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(p: Int) = when (items[p]) { is Item.Folder -> 0; is Item.Song -> 1; is Item.Artist -> 2 }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val v: View = when (type) {
                0 -> LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(12))
                    val mosaic = GridLayout(c).apply { columnCount = 2; rowCount = 2; clipToOutline = true; background = roundRect(0x22FFFFFF, dp(12).toFloat()); tag = "mosaic" }
                    repeat(4) { mosaic.addView(ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, GridLayout.LayoutParams().apply { width = 0; height = 0; columnSpec = GridLayout.spec(it % 2, 1f); rowSpec = GridLayout.spec(it / 2, 1f) }) }
                    addView(mosaic, lp(MATCH, dp(150)))
                    addView(c.text("", 15f, Color.WHITE, 2).apply { tag = "name"; setPadding(dp(2), dp(8), 0, 0) })
                    val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(c.text("", 12f, 0xAAFFFFFF.toInt()).apply { tag = "count" }, lp(0, WRAP, 1f))
                    row.addView(c.text("＋ Toda", 12f, Color.WHITE, 2).apply {
                        tag = "all"; gravity = Gravity.CENTER; setPadding(dp(10), dp(5), dp(10), dp(5)); background = roundRect(BRAND_RED, dp(14).toFloat())
                    })
                    addView(row, lp(MATCH, WRAP).apply { topMargin = dp(4) })
                }
                1 -> LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(7), dp(8), dp(7))
                    addView(ImageView(c).apply { tag = "img"; scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = roundRect(0x22FFFFFF, dp(6).toFloat()) }, lp(dp(50), dp(50)))
                    val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
                    t.addView(c.text("", 15f, Color.WHITE, 1).apply { tag = "t" }); t.addView(c.text("", 12f, 0xAAFFFFFF.toInt()).apply { tag = "s" })
                    addView(t, lp(0, WRAP, 1f))
                    addView(ImageView(c).apply { tag = "chk" }, lp(dp(26), dp(26)))
                }
                else -> LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
                    addView(c.text("", 20f, Color.WHITE, 2).apply { tag = "ini"; gravity = Gravity.CENTER }, lp(dp(50), dp(50)))
                    val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                    t.addView(c.text("", 15f, Color.WHITE, 1).apply { tag = "t" }); t.addView(c.text("", 12f, 0xAAFFFFFF.toInt()).apply { tag = "s" })
                    addView(t, lp(0, WRAP, 1f))
                    addView(c.text("›", 22f, 0x88FFFFFF.toInt()))
                }
            }
            v.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            val v = h.itemView
            when (val it = items[p]) {
                is Item.Folder -> {
                    val mosaic = v.findViewWithTag<GridLayout>("mosaic")
                    for (i in 0 until 4) {
                        val iv = mosaic.getChildAt(i) as ImageView
                        it.songs.getOrNull(i)?.let { s -> thumb(iv, s) } ?: iv.setImageBitmap(KaleidoRenderer.bitmap(it.name.hashCode().toLong() + i, 96))
                    }
                    v.findViewWithTag<TextView>("name").text = it.name
                    v.findViewWithTag<TextView>("count").text = "${it.songs.size} canciones"
                    v.findViewWithTag<View>("all").setOnClickListener { b -> b.haptic(true); b.bounce(); add(it.songs.filter { s -> s.id !in already }) }
                    v.setOnClickListener { x -> x.haptic(); folder = it.name; rebuild() }
                }
                is Item.Song -> {
                    val s = it.s
                    thumb(v.findViewWithTag("img"), s)
                    v.findViewWithTag<TextView>("t").text = s.title
                    val isIn = s.id in already
                    v.findViewWithTag<TextView>("s").text = if (isIn) "Ya está en la playlist" else listOfNotNull(s.artist.ifEmpty { null }, Util.fmtDuration(s.durationMs / 1000.0)).joinToString(" · ")
                    val chk = v.findViewWithTag<ImageView>("chk")
                    chk.setImageResource(if (isIn || s.id in selected) R.drawable.ic_circle_on else R.drawable.ic_circle_off)
                    v.alpha = if (isIn) 0.45f else 1f
                    v.setOnClickListener { x ->
                        if (isIn) return@setOnClickListener
                        x.haptic()
                        if (!selected.remove(s.id)) selected += s.id
                        chk.setImageResource(if (s.id in selected) R.drawable.ic_circle_on else R.drawable.ic_circle_off)
                        chk.scaleX = 0.5f; chk.scaleY = 0.5f
                        chk.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(4f)).start()
                        refreshBar(); bumpCounter()
                    }
                }
                is Item.Artist -> {
                    v.findViewWithTag<TextView>("ini").apply {
                        text = it.name.take(1).uppercase()
                        background = oval(Color.HSVToColor(floatArrayOf((it.name.hashCode() and 0xFFFF) % 360f, 0.55f, 0.6f)))
                    }
                    v.findViewWithTag<TextView>("t").text = it.name
                    v.findViewWithTag<TextView>("s").text = "${it.count} canciones"
                    v.setOnClickListener { x -> x.haptic(); artist = it.name; rebuild() }
                }
            }
        }
    }

    @Suppress("unused") private fun noop(f: File) = Unit
}
