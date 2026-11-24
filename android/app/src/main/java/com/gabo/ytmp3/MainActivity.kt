package com.gabo.ytmp3

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Pantalla principal: pestañas Biblioteca / Descargar abajo, mini-reproductor encima
 * y el reproductor a pantalla completa por encima de todo.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        private const val TAG_HOME = "home"
        private const val TAG_LIB = "lib"
        private const val TAG_DL = "dl"
        private const val TAG_PLAYER = "player"
    }

    private lateinit var content: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var mini: LinearLayout
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageView
    private lateinit var miniProgress: ProgressBar
    private lateinit var nav: LinearLayout
    private lateinit var tabHome: LinearLayout
    private lateinit var tabLib: LinearLayout
    private lateinit var tabNew: LinearLayout
    private lateinit var tabDl: LinearLayout
    private var tab = 0
    private var lastTrackId = -1L
    var accent: Int = 0xFF3A1014.toInt()
        private set

    private val playerListener: () -> Unit = { renderMini() }
    private lateinit var bottom: View
    private lateinit var handle: View
    private val bottomListeners = mutableListOf<(Int) -> Unit>()
    private var bottomH = 0
    private var statusTop = 0

    /** Las listas se dibujan por debajo de la barra de cristal: les avisamos cuánto espacio dejar abajo. */
    fun onBottomInset(l: (Int) -> Unit) { bottomListeners += l; l(bottomH) }
    private fun publishBottom(h: Int) { if (h != bottomH) { bottomH = h; bottomListeners.toList().forEach { it(h) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_YTMP3)
        super.onCreate(savedInstanceState)
        if (!Prefs2(this).onboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish(); return
        }
        buildUi()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(content.id, HomeFragment(), TAG_HOME)
                .add(content.id, LibraryFragment(), TAG_LIB)
                .add(content.id, DownloadFragment(), TAG_DL)
                .commitNow()
            selectTab(0)
            AppUpdater.check(this, manual = false)
            content.post { handleIntent(intent) }
        } else {
            selectTab(savedInstanceState.getInt("tab", 0))
        }
        PlayerHub.connect(this)
        bootOk()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fm = supportFragmentManager
                when {
                    fm.backStackEntryCount > 0 -> fm.popBackStack()
                    tab == 2 && (fm.findFragmentByTag(TAG_DL) as? BackAware)?.onBack() == true -> {}
                    tab != 0 -> selectTab(0)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        if (i.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) { i.removeExtra(EXTRA_OPEN_PLAYER); openPlayer(); return }
        if (i.action == Intent.ACTION_SEND || i.getBooleanExtra(DownloadService.EXTRA_OPEN_VERIFY, false)) {
            selectTab(2)
            (supportFragmentManager.findFragmentByTag(TAG_DL) as? DownloadFragment)?.handleIntent(i)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::mini.isInitialized) return
        PlaybackService.addListener(playerListener)
        renderMini()
        mini.post(progressTick)
    }

    override fun onStop() {
        PlaybackService.removeListener(playerListener)
        if (::mini.isInitialized) mini.removeCallbacks(progressTick)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) PlayerHub.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interfaz
    private fun buildUi() {
        // Pantalla completa: la app se dibuja detrás de la hora/batería y de los botones del sistema
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val root = FrameLayout(this).apply { setBackgroundColor(col(R.color.ios_bg)) }
        content = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        val saver = Prefs2(this).saver
        val glass = eightbitlab.com.blurview.BlurView(this)
        val bottomBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        glass.addView(bottomBox, FrameLayout.LayoutParams(MATCH, WRAP))
        if (!saver) runCatching {
            val algo = if (android.os.Build.VERSION.SDK_INT >= 31) eightbitlab.com.blurview.RenderEffectBlur()
                       else @Suppress("DEPRECATION") eightbitlab.com.blurview.RenderScriptBlur(this)
            glass.setupWith(root, algo).setBlurRadius(22f).setFrameClearDrawable(window.decorView.background)
        }
        val base = col(R.color.ios_bg)
        glass.setBackgroundColor(Color.argb(if (saver) 245 else 150, Color.red(base), Color.green(base), Color.blue(base)))
        val bottom = bottomBox
        this.bottom = glass
        // mini-reproductor
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundRect(accent, dp(10).toFloat())
            elevation = dp(6).toFloat()
            clipToOutline = true
        }
        val miniRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(6), dp(7))
        }
        miniCover = ImageView(this)
        miniRow.addView(miniCover, lp(dp(40), dp(40)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
        miniTitle = text("", 14f, Color.WHITE, 1)
        miniSub = text("", 12f, 0xCCFFFFFF.toInt())
        texts.addView(miniTitle); texts.addView(miniSub)
        miniRow.addView(texts, lp(0, WRAP, 1f))
        miniPlay = icon(R.drawable.ic_play, 44, Color.WHITE, 8)
        miniPlay.setOnClickListener { it.haptic(); PlayerHub.service?.togglePlay() }
        miniRow.addView(miniPlay)
        mini.addView(miniRow)
        miniProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
        }
        mini.addView(miniProgress, lp(MATCH, dp(2)))
        miniGestures()
        bottom.addView(mini, lp(MATCH, WRAP).apply { setMargins(dp(8), 0, dp(8), dp(4)) })

        // barra de pestañas
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        tabHome = navTab(R.drawable.ic_home, "Inicio") { selectTab(0) }
        tabLib = navTab(R.drawable.ic_library, "Biblioteca") { selectTab(1) }
        tabDl = navTab(R.drawable.ic_download_globe, "Descargar") { selectTab(2) }
        tabNew = navTab(R.drawable.ic_add_box, "Crear") { createPlaylist() }
        listOf(tabHome, tabLib, tabDl, tabNew).forEach { nav.addView(it, lp(0, WRAP, 1f)) }
        bottom.addView(nav, lp(MATCH, WRAP))
        root.addView(glass, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        // barrita para mostrar los botones en las réplicas (pantalla completa)
        handle = FrameLayout(this).apply {
            visibility = View.GONE
            addView(View(context).apply { background = roundRect(0x99FFFFFF.toInt(), dp(3).toFloat()) },
                FrameLayout.LayoutParams(dp(64), dp(5), Gravity.CENTER))
            var downY = 0f
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> downY = e.rawY
                    android.view.MotionEvent.ACTION_UP -> if (downY - e.rawY > dp(12) || kotlin.math.abs(downY - e.rawY) < dp(8)) revealNav()
                }
                true
            }
        }
        root.addView(handle, FrameLayout.LayoutParams(dp(160), dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        overlay = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        paintNav()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusTop = bars.top
            nav.setPadding(0, dp(6), 0, dp(6) + bars.bottom)
            content.setPadding(0, bars.top, 0, 0)
            (handle.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom
            handle.requestLayout()
            insets
        }
        glass.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (tab != 2) publishBottom(glass.height) }
    }

    private fun navTab(iconRes: Int, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(icon(iconRes, 26, col(R.color.ios_label)).apply { tag = "icon" })
        addView(text(label, 11f, col(R.color.ios_label), 1).apply { tag = "label"; gravity = Gravity.CENTER })
        setOnClickListener { it.haptic(); onClick() }
    }

    private fun paintNav() {
        val dyn = Prefs2(this).theme == AppTheme.DYNAMIC && PlayerHub.service?.current() != null
        val base = col(R.color.ios_bg)
        nav.background = if (dyn) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(235, Color.red(base), Color.green(base), Color.blue(base)), darker(accent, 0.45f)))
            else GradientDrawable().apply { setColor(Color.argb(245, Color.red(base), Color.green(base), Color.blue(base))) }
        if (dyn) window.decorView.setBackgroundColor(darker(accent, 0.35f))
        listOf(tabHome, tabLib, tabDl, tabNew).forEachIndexed { i, t ->
            val on = i == tab
            val c = if (on) col(R.color.ios_label) else col(R.color.ios_secondary)
            (t.findViewWithTag<ImageView>("icon")).setColorFilter(c)
            (t.findViewWithTag<TextView>("label")).setTextColor(c)
        }
    }

    fun selectTab(i: Int) {
        tab = i
        applyImmersive()
        val fm = supportFragmentManager
        while (fm.backStackEntryCount > 0 && fm.findFragmentByTag(TAG_PLAYER) == null) fm.popBackStackImmediate()
        val tr = fm.beginTransaction()
        fm.fragments.filter { it.tag?.startsWith("pl_") == true }.forEach { tr.remove(it) }
        listOf(TAG_HOME, TAG_LIB, TAG_DL).forEachIndexed { idx, tag ->
            fm.findFragmentByTag(tag)?.let { f -> if (idx == i) tr.show(f) else tr.hide(f) }
        }
        tr.commitNowAllowingStateLoss()
        paintNav()
    }

    /** Abre una playlist encima de la biblioteca (atrás vuelve a la biblioteca). */
    fun openPlaylist(id: Long) {
        val fm = supportFragmentManager
        if (tab == 2) selectTab(1)
        val lib = fm.findFragmentByTag(if (tab == 0) TAG_HOME else TAG_LIB) ?: return
        fm.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.fade_out_short, R.anim.fade_in_short, R.anim.slide_out_right)
            .hide(lib)
            .add(content.id, PlaylistFragment.new(id), "pl_$id")
            .addToBackStack("pl")
            .commit()
    }

    // ------------------------------------------------------------------ réplicas a pantalla completa
    private val hideNav = Runnable { if (tab == 2) bottom.animate().translationY(bottom.height.toFloat() + dp(20)).setDuration(260).start(); if (tab == 2) handle.visibility = View.VISIBLE }

    private fun applyImmersive() {
        if (!::bottom.isInitialized) return
        bottom.removeCallbacks(hideNav)
        if (tab == 2) {
            mini.visibility = View.GONE
            bottom.post { bottom.animate().translationY(bottom.height.toFloat() + dp(20)).setDuration(220).start() }
            handle.visibility = View.VISIBLE
            publishBottom(0)
        } else {
            bottom.animate().translationY(0f).setDuration(220).start()
            handle.visibility = View.GONE
            renderMini()
            bottom.post { publishBottom(bottom.height) }
        }
    }

    private fun revealNav() {
        handle.haptic()
        handle.visibility = View.GONE
        bottom.animate().translationY(0f).setDuration(220).start()
        bottom.removeCallbacks(hideNav)
        bottom.postDelayed(hideNav, 4500)
    }

    private fun miniGestures() {
        val gd = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { openPlayer(); return true }
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (kotlin.math.abs(dx) < dp(40) || kotlin.math.abs(vx) < kotlin.math.abs(vy)) return false
                val s = PlayerHub.service ?: return false
                mini.haptic()
                val w = mini.width.toFloat()
                mini.animate().translationX(if (dx < 0) -w * 0.4f else w * 0.4f).alpha(0.2f).setDuration(140).withEndAction {
                    if (dx < 0) s.next() else s.prev()
                    mini.translationX = if (dx < 0) w * 0.4f else -w * 0.4f
                    mini.animate().translationX(0f).alpha(1f).setDuration(200).start()
                }.start()
                return true
            }
        })
        mini.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
    }

    // ------------------------------------------------------------------ Modo DJ
    fun openDj(mood: Mood?, tracks: List<Track>?, start: Track?, name: String, plId: Long = -4) {
        DjFragment.pendingTracks = tracks; DjFragment.pendingStart = start
        val fm = supportFragmentManager
        fm.findFragmentByTag("dj")?.let { fm.popBackStackImmediate("dj", androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE) }
        fm.beginTransaction()
            .setCustomAnimations(R.anim.slide_up, R.anim.fade_out_short, R.anim.fade_in_short, R.anim.slide_down)
            .add(overlay.id, DjFragment.new(mood, name, plId), "dj")
            .addToBackStack("dj").commit()
    }

    fun openMoods() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_in_short, R.anim.fade_out_short, R.anim.fade_in_short, R.anim.fade_out_short)
            .add(overlay.id, MoodFragment(), "moods").addToBackStack("moods").commit()
    }

    private fun openDjScreen() {
        val fm = supportFragmentManager
        if (fm.findFragmentByTag("dj") != null) return
        val f = DjFragment.new(PlayerHub.service?.djMood, PlayerHub.service?.playlistName ?: "Modo DJ", -4).apply { arguments?.putBoolean("start", false) }
        fm.beginTransaction()
            .setCustomAnimations(R.anim.slide_up, R.anim.fade_out_short, R.anim.fade_in_short, R.anim.slide_down)
            .add(overlay.id, f, "dj").addToBackStack("dj").commit()
    }

    /** Pide una vez el permiso para la pantalla de bloqueo propia. */
    private fun askOverlayOnce() {
        val p = Prefs2(this)
        if (!p.lockScreen || p.overlayAsked || android.provider.Settings.canDrawOverlays(this)) return
        p.overlayAsked = true
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Tu música en la pantalla de bloqueo 🔒")
            .setMessage("Para mostrar la portada y los controles con el celular bloqueado (en todas las marcas, también Huawei), " +
                "activa «Mostrar sobre otras apps» para DownSong. En Huawei activa también «Inicio automático» en Ajustes › Batería.")
            .setPositiveButton("Activar") { _, _ ->
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
            }
            .setNegativeButton("Ahora no", null).show()
    }

    /** Si la app llegó a abrir bien, se borra el contador de cierres; y si la vez anterior falló, se ofrece enviar el error. */
    private fun bootOk() {
        window.decorView.postDelayed({ getSharedPreferences("boot", MODE_PRIVATE).edit().putInt("fails", 0).apply() }, 60_000)
        val f = java.io.File(filesDir, "crash_last.txt")
        if (!f.exists()) return
        val txt = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        BlackBox.log(this, "detalle del cierre anterior:\n" + txt.take(3000))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("DownSong se cerró la última vez")
            .setMessage("Ya lo arreglamos para que no vuelva a pasar. ¿Nos envías el informe para revisarlo?")
            .setPositiveButton("Enviar informe") { _, _ -> BlackBox.share(this) }
            .setNegativeButton("Ahora no", null).show()
    }

    fun openPlayer() {
        Background.beforePlayback(this)
        if (PlayerHub.service?.dj != null) { openDjScreen(); return }
        if (PlayerHub.service?.current() == null) return
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(TAG_PLAYER) != null) return
        fm.beginTransaction()
            .setCustomAnimations(R.anim.slide_up, R.anim.fade_out_short, R.anim.fade_in_short, R.anim.slide_down)
            .add(overlay.id, PlayerFragment(), TAG_PLAYER)
            .addToBackStack("player")
            .commit()
    }

    fun closePlayer() {
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(TAG_PLAYER) != null) fm.popBackStack("player", androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private fun createPlaylist() = PlaylistPicker.askName(this, "Nueva playlist", "") { name ->
        lifecycleScope.launch {
            val id = Library.createPlaylist(this@MainActivity, name)
            selectTab(1); openPlaylist(id)
        }
    }

    fun refreshLibrary() { /* la biblioteca se actualiza sola (Room Flow) */ }

    // ------------------------------------------------------------------ mini-reproductor
    private val progressTick = object : Runnable {
        override fun run() {
            val s = PlayerHub.service
            if (s != null && mini.visibility == View.VISIBLE) {
                val d = s.durMs
                miniProgress.progress = if (d > 0) (s.active.currentPosition * 1000 / d).toInt() else 0
            }
            mini.postDelayed(this, 500)
        }
    }

    private fun renderMini() {
        val s = PlayerHub.service
        val t = s?.current()
        mini.visibility = if (t == null || tab == 2) View.GONE else View.VISIBLE
        if (t == null || s == null) { paintNav(); return }
        if (s.active.isPlaying) askOverlayOnce()
        miniTitle.text = t.title
        miniSub.text = t.artist.ifEmpty { s.playlistName }
        miniPlay.setImageResource(if (s.active.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        if (t.id != lastTrackId) {
            lastTrackId = t.id
            miniCover.cover(t, 4f)
            lifecycleScope.launch {
                accent = Art.color(Covers.fileFor(this@MainActivity, t))
                mini.background = roundRect(darker(accent, 0.85f), dp(10).toFloat())
                paintNav()
                supportFragmentManager.fragments.forEach { (it as? AccentAware)?.onAccent(accent) }
            }
        }
    }
}

/** Fragmentos que reaccionan al color de la canción (tema dinámico). */
interface AccentAware { fun onAccent(color: Int) }

/** Fragmentos que manejan el botón atrás (p. ej. retroceder dentro de una réplica web). */
interface BackAware { fun onBack(): Boolean }

@Suppress("unused") private fun Fragment.main() = activity as? MainActivity
