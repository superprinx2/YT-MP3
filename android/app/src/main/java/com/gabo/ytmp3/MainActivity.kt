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
    private lateinit var tabLib: LinearLayout
    private lateinit var tabDl: LinearLayout
    private var tab = 0
    private var lastTrackId = -1L
    var accent: Int = 0xFF3A1014.toInt()
        private set

    private val playerListener: () -> Unit = { renderMini() }

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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fm = supportFragmentManager
                when {
                    fm.backStackEntryCount > 0 -> fm.popBackStack()
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
            selectTab(1)
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
        val root = FrameLayout(this).apply { setBackgroundColor(col(R.color.ios_bg)) }
        content = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        mini.setOnClickListener { openPlayer() }
        bottom.addView(mini, lp(MATCH, WRAP).apply { setMargins(dp(8), 0, dp(8), dp(4)) })

        // barra de pestañas
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        tabLib = navTab(R.drawable.ic_library, "Biblioteca") { selectTab(0) }
        tabDl = navTab(R.drawable.ic_download_globe, "Descargar") { selectTab(1) }
        nav.addView(tabLib, lp(0, WRAP, 1f)); nav.addView(tabDl, lp(0, WRAP, 1f))
        bottom.addView(nav, lp(MATCH, WRAP))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        overlay = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        paintNav()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            nav.setPadding(0, dp(6), 0, dp(6) + bars.bottom)
            bottom.post { content.setPadding(0, 0, 0, bottom.height) }
            insets
        }
        bottom.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> content.setPadding(0, 0, 0, bottom.height) }
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
        listOf(tabLib, tabDl).forEachIndexed { i, t ->
            val on = i == tab
            val c = if (on) col(R.color.ios_label) else col(R.color.ios_secondary)
            (t.findViewWithTag<ImageView>("icon")).setColorFilter(c)
            (t.findViewWithTag<TextView>("label")).setTextColor(c)
        }
    }

    fun selectTab(i: Int) {
        tab = i
        val fm = supportFragmentManager
        while (fm.backStackEntryCount > 0 && fm.findFragmentByTag(TAG_PLAYER) == null) fm.popBackStackImmediate()
        val lib = fm.findFragmentByTag(TAG_LIB); val dl = fm.findFragmentByTag(TAG_DL)
        val tr = fm.beginTransaction()
        fm.fragments.filter { it.tag?.startsWith("pl_") == true }.forEach { tr.remove(it) }
        if (lib != null) { if (i == 0) tr.show(lib) else tr.hide(lib) }
        if (dl != null) { if (i == 1) tr.show(dl) else tr.hide(dl) }
        tr.commitNowAllowingStateLoss()
        paintNav()
    }

    /** Abre una playlist encima de la biblioteca (atrás vuelve a la biblioteca). */
    fun openPlaylist(id: Long) {
        val fm = supportFragmentManager
        val lib = fm.findFragmentByTag(TAG_LIB) ?: return
        fm.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.fade_out_short, R.anim.fade_in_short, R.anim.slide_out_right)
            .hide(lib)
            .add(content.id, PlaylistFragment.new(id), "pl_$id")
            .addToBackStack("pl")
            .commit()
    }

    fun openPlayer() {
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

    fun refreshLibrary() { /* la biblioteca se actualiza sola (Room Flow) */ }

    // ------------------------------------------------------------------ mini-reproductor
    private val progressTick = object : Runnable {
        override fun run() {
            val s = PlayerHub.service
            if (s != null && mini.visibility == View.VISIBLE) {
                val d = s.active.duration
                miniProgress.progress = if (d > 0) (s.active.currentPosition * 1000 / d).toInt() else 0
            }
            mini.postDelayed(this, 500)
        }
    }

    private fun renderMini() {
        val s = PlayerHub.service
        val t = s?.current()
        mini.visibility = if (t == null) View.GONE else View.VISIBLE
        if (t == null || s == null) { paintNav(); return }
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

@Suppress("unused") private fun Fragment.main() = activity as? MainActivity
