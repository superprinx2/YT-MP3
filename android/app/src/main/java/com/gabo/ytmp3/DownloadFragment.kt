package com.gabo.ytmp3

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.gabo.ytmp3.databinding.ActivityMainBinding
import com.gabo.ytmp3.databinding.HeaderMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DownloadFragment : Fragment(), BackAware {

    private val ctx get() = requireActivity() as AppCompatActivity
    private var targets: Set<Long> = emptySet()
    private var targetNames: String = ""
    private lateinit var web: WebReplica
    private lateinit var backChip: android.widget.TextView
    private var showingResults = false
    private var homeLoaded = false
    private var previewJob: kotlinx.coroutines.Job? = null
    /** Búsqueda de YouTube que se está mostrando (null = inicio). */
    private var ytQuery: String? = null
    private var ytFilter = 0
    /** La lista viene de una búsqueda o del inicio (no de un enlace de playlist). */
    private var fromSearch = false
    /** El enlace vino de la réplica web: se descarga sin salir de esa red. */
    private var fromWeb = false
    private lateinit var backBtn: android.widget.ImageView
    private lateinit var logoBadge: android.widget.ImageView
    private lateinit var filterBtn: android.widget.ImageView
    private lateinit var hintMarquee: android.widget.TextView

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
        h.urlInput.setText(said); analyze()
    }
    private var onFolderPicked: ((String) -> Unit)? = null
    private val folderPick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra(ImportActivity.EXTRA_PATH) ?: return@registerForActivityResult
        Prefs2(ctx).saveLocalPath = path; Prefs2(ctx).saveLocal = true
        onFolderPicked?.invoke(path)
    }
    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }


    private lateinit var b: ActivityMainBinding
    private lateinit var h: HeaderMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: VideoAdapter
    private var analyzing = false
    private var analyzeAfterVerify = false
    /** Instagram pidió iniciar sesión en el último intento (solo entonces se muestra la tarjeta). */
    private var instagramAskedLogin = false
    private var lastRenderedRunning = false
    private val lastActive = HashSet<VideoItem>()
    private val heroView: HeroView? get() = if (::h.isInitialized) h.hero.getChildAt(0) as? HeroView else null

    private val verify = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        showLoginCard()
        if (r.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        if (DownloadState.running) DownloadService.verificationDone()
        else if (analyzeAfterVerify) { analyzeAfterVerify = false; analyze() }
    }

    private val stateListener: () -> Unit = { render() }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?,
                              savedInstanceState: Bundle?): View {
        b = ActivityMainBinding.inflate(inflater, container, false)
        prefs = Prefs(requireContext())

        b.list.layoutManager = LinearLayoutManager(requireContext())
        h = HeaderMainBinding.inflate(inflater, b.list, false)
        adapter = VideoAdapter(DownloadState.items, { updateCount() }, { togglePreview(it) }, { openVideo(it) })
        b.list.adapter = ConcatAdapter(HeaderAdapter(h.root), adapter,
            FooterAdapter({ SettingsActivity.copyHandle(ctx, it) }, { it.haptic(); Donate.show(ctx) }))
        b.list.itemAnimator = null
        setupReplica()

        // --- interacción
        listOf(h.btnAnalyze, h.btnPaste, h.btnSettings, h.modeChip, h.rowFolder, h.rowQuality, b.btnCancel)
            .forEach { it.pressable(0.96f) }
        h.logo.setOnClickListener { it.haptic(); it.bounce() }
        h.btnDonate.visibility = View.GONE
        h.btnDonate.setOnClickListener { it.haptic(); Donate.show(ctx) }
        h.btnPaste.setOnClickListener {
            it.haptic()
            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { c -> c.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
            val url = Util.extractUrl(text)
            if (url == null) toast("No hay ningún enlace copiado")
            else { h.urlInput.setText(url); analyze() }
        }
        h.btnAnalyze.setOnClickListener { it.haptic(); analyze() }
        h.urlInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { analyze(); true } else false
        }
        h.btnSelectAll.setOnClickListener {
            it.haptic()
            val all = DownloadState.items.all { i -> i.checked }
            DownloadState.items.forEach { i -> i.checked = !all }
            adapter.notifyItemRangeChanged(0, DownloadState.items.size)
            updateCount()
        }
        val openSettings = View.OnClickListener { v -> v.haptic(); startActivity(Intent(ctx, SettingsActivity::class.java)) }
        h.btnSettings.setOnClickListener(openSettings)
        h.modeChip.setOnClickListener(openSettings)
        h.rowFolder.setOnClickListener { it.haptic(); pickTargets(confirm = false) }
        h.rowQuality.setOnClickListener { it.haptic(); chooseQuality() }
        h.segMp3.setOnClickListener { if (!DownloadState.running) { it.haptic(); setFormat(Format.MP3) } }
        h.segMp4.setOnClickListener {
            if (DownloadState.running) return@setOnClickListener
            if (prefs.platform == Platform.SPOTIFY) { toast("De Spotify se descarga solo el audio (MP3)"); return@setOnClickListener }
            it.haptic(); setFormat(Format.MP4)
        }
        b.btnDownload.setOnClickListener { it.haptic(true); startDownload() }
        pressScale(b.btnDownload, b.downloadFrame)
        b.btnCancel.setOnClickListener { it.haptic(); confirmCancel() }
        b.progressCard.setOnClickListener { if (!DownloadState.running) { it.haptic(); dismissDone() } }
        b.verifyBanner.setOnClickListener { it.haptic(); openVerify(DownloadState.verifyPlatform) }
        h.btnClear.setOnClickListener {
            it.haptic()
            h.urlInput.setText("")
            h.urlError.visibility = View.GONE
            h.urlInput.requestFocus()
            ctx.getSystemService(InputMethodManager::class.java).showSoftInput(h.urlInput, 0)
        }
        h.urlInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (::hintMarquee.isInitialized) hintMarquee.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
                h.btnClear.visibility = if (s.isNullOrEmpty() || DownloadState.running) View.GONE else View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        h.loginCard.setOnClickListener { it.haptic(); openVerify(Platform.INSTAGRAM) }
        h.loginCard.pressable(0.97f)
        mapOf(h.tabYoutube to Platform.YOUTUBE, h.tabTiktok to Platform.TIKTOK, h.tabInstagram to Platform.INSTAGRAM,
            h.tabFacebook to Platform.FACEBOOK, h.tabSpotify to Platform.SPOTIFY)
            .forEach { (v, p) ->
                v.pressable(0.95f)
                v.setOnClickListener { if (!DownloadState.running) { it.haptic(); showingResults = false; setPlatform(p) } }
            }

        setPlatform(prefs.platform, animate = false)
        setFormat(prefs.format)
        showFolder()

        // Restaurar textos si la pantalla se recrea (giro, modo oscuro…)
        if (DownloadState.items.isNotEmpty()) {
            h.listTitle.text = if (DownloadState.isPlaylist) "${DownloadState.items.size} ${noun()}" else noun(1)
            h.infoText.text = if (DownloadState.isPlaylist) "Playlist «${DownloadState.listTitle}»" else ""
            h.infoText.visibility = if (DownloadState.isPlaylist) View.VISIBLE else View.GONE
        }

        loadTargets()
        return b.root
    }

    /** El botón principal se hunde junto con su marco de brillo. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressScale(touch: View, target: View) {
        touch.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (v.isEnabled) target.animate().scaleX(0.96f).scaleY(0.96f)
                    .setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> target.animate().scaleX(1f).scaleY(1f)
                    .setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    override fun onStart() {
        super.onStart()
        DownloadState.addListener(stateListener)
        render()
    }

    override fun onResume() {
        super.onResume()
        showHero()
        b.root.postDelayed({ offerClipboard() }, 400)
    }

    private var offeredUrl: String? = null

    /** Si copiaste un enlace (por ejemplo "Compartir → Copiar enlace" en Spotify), ofrecer descargarlo. */
    private fun offerClipboard() {
        if (!isAdded || isHidden || DownloadState.running || analyzing) return
        val clip = runCatching { ctx.getSystemService(ClipboardManager::class.java).primaryClip }.getOrNull()
        val url = Util.extractUrl(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()) ?: return
        if (url == offeredUrl || Platform.detect(url) == null) return
        offeredUrl = url
        val p = Platform.detect(url)!!
        val bar = com.google.android.material.snackbar.Snackbar.make(b.root, "Enlace de ${p.label} copiado", 8000)
        bar.setAction("Descargar") { it.haptic(); fromWeb = true; analyze(url) }
        bar.setActionTextColor(BRAND_RED)
        bar.show()
    }

    override fun onPause() {
        h.btnDonate.removeCallbacks(donateWiggle)
        stopPreview()
        if (::web.isInitialized) web.pause()
        super.onPause()
    }

    /** El botón "Tengo hambre" rebota de vez en cuando para llamar la atención. */
    private val donateWiggle = object : Runnable {
        override fun run() {
            if (!DownloadState.running) {
                h.btnDonate.animate().rotation(-6f).setDuration(80).withEndAction {
                    h.btnDonate.animate().rotation(6f).setDuration(80).withEndAction {
                        h.btnDonate.animate().rotation(0f).setDuration(80).start()
                    }.start()
                }.start()
                h.btnDonate.bounce()
            }
            h.btnDonate.postDelayed(this, 12000)
        }
    }

    override fun onStop() {
        DownloadState.removeListener(stateListener)
        super.onStop()
    }

    /** Lo llama la pantalla principal (enlace compartido o aviso de verificación). */
    fun handleIntent(intent: Intent?) {
        if (!isAdded || !::h.isInitialized) return
        if (intent?.getBooleanExtra(DownloadService.EXTRA_OPEN_VERIFY, false) == true) {
            intent.removeExtra(DownloadService.EXTRA_OPEN_VERIFY)
            openVerify(DownloadState.verifyPlatform)
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val url = Util.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        intent.action = null
        if (DownloadState.running) { toast("Hay una descarga en curso"); return }
        h.urlInput.setText(url)
        analyze()
    }

    private fun openVerify(p: Platform) = verify.launch(VerifyActivity.intent(ctx, p))

    // ------------------------------------------------------------------ plataforma
    private fun setPlatform(p: Platform, animate: Boolean = true) {
        prefs.platform = p
        val tabs = mapOf(Platform.YOUTUBE to h.tabYoutube, Platform.TIKTOK to h.tabTiktok,
            Platform.INSTAGRAM to h.tabInstagram, Platform.FACEBOOK to h.tabFacebook, Platform.SPOTIFY to h.tabSpotify)
        tabs.forEach { (tp, v) ->
            v.isSelected = tp == p
            v.alpha = if (tp == p) 1f else 0.7f
        }
        if (animate) tabs[p]?.bounce()
        heroView?.setPalette(p.palette)
        if (::hintMarquee.isInitialized) { hintMarquee.text = "Buscar en ${p.label} o pegar enlace"; hintMarquee.isSelected = true }
        if (::logoBadge.isInitialized) {
            logoBadge.setBackgroundResource(when (p) {
                Platform.YOUTUBE -> R.drawable.bg_tab_youtube; Platform.SPOTIFY -> R.drawable.bg_tab_spotify
                Platform.TIKTOK -> R.drawable.bg_tab_tiktok; Platform.INSTAGRAM -> R.drawable.bg_tab_instagram
                Platform.FACEBOOK -> R.drawable.bg_tab_facebook
            })
            logoBadge.setImageResource(when (p) {
                Platform.YOUTUBE -> R.drawable.ic_tab_play; Platform.SPOTIFY -> R.drawable.ic_tab_waves
                Platform.TIKTOK -> R.drawable.ic_tab_note; Platform.INSTAGRAM -> R.drawable.ic_tab_camera
                Platform.FACEBOOK -> R.drawable.ic_tab_video
            })
            filterBtn.visibility = View.GONE   // YouTube ya trae sus propios filtros
        }
        // Al volver a YouTube no mostrar lo que se bajó de otra red
        if (p == Platform.YOUTUBE && !DownloadState.running && DownloadState.items.any { it.platform != Platform.YOUTUBE }) {
            DownloadState.items.clear(); homeLoaded = false; ytQuery = null
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        }
        if (DownloadState.items.isEmpty() && !analyzing) {
            h.infoText.visibility = View.VISIBLE
            h.infoText.text = when (p) {
                Platform.YOUTUBE -> "Busca canciones y videos sin anuncios. Toca ▶ en la portada para escuchar antes de descargar."
                Platform.TIKTOK -> "Videos sin marca de agua en MP4, o solo el audio en MP3. También funciona «Compartir» desde TikTok."
                Platform.INSTAGRAM -> "Reels y videos de publicaciones en MP4 o MP3. También funciona «Compartir» desde Instagram. " +
                    "Si Instagram pidiera iniciar sesión, te avisaremos."
                Platform.FACEBOOK -> "Videos y reels públicos de Facebook en MP4 o MP3. También funciona «Compartir» desde Facebook."
                Platform.SPOTIFY -> "Playlists, álbumes y canciones de Spotify en MP3: la app lee la lista y baja cada canción " +
                    "desde YouTube (Spotify no permite bajar su audio). Playlists completas, sin límite de 100."
            }
        }
        // Spotify: solo MP3
        val sp = p == Platform.SPOTIFY
        if (sp && prefs.format != Format.MP3) setFormat(Format.MP3)
        h.segMp4.isEnabled = !sp && !DownloadState.running
        h.segMp4.alpha = if (sp) 0.35f else 1f
        showLoginCard()
        updateMode()
    }

    // ------------------------------------------------------------------ réplicas
    private lateinit var panelBox: android.widget.FrameLayout
    private lateinit var pill: View
    private lateinit var spacer: View
    private var panelH = 0
    private var off = 0f

    private fun setupReplica() {
        h.hero.visibility = View.GONE
        val column = b.list.parent as android.widget.LinearLayout
        (h.platformBar.parent as android.view.ViewGroup).removeView(h.platformBar)
        val label = ctx.col(R.color.ios_label)
        val fieldBg = ctx.col(R.color.ios_card)
        // Fila 1: un solo buscador, con la apariencia del de YouTube
        val searchRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(6), ctx.dp(8), ctx.dp(4), ctx.dp(6))
        }
        // Atrás siempre visible: retrocede en la página y, si no hay a dónde, vuelve a tu biblioteca
        backBtn = ctx.icon(R.drawable.ic_back, 44, label, 9).apply {
            contentDescription = "Atrás"
            background = oval(fieldBg)
            setOnClickListener { it.haptic(); goBack() }
        }
        searchRow.addView(backBtn, lp(ctx.dp(44), ctx.dp(44)).apply { marginEnd = ctx.dp(8) })
        // (el logo de la red ya está en la fila de redes: no se repite arriba)
        logoBadge = android.widget.ImageView(ctx)
        val pillBox = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            background = roundRect(fieldBg, ctx.dp(22).toFloat()); setPadding(ctx.dp(16), 0, ctx.dp(4), 0)
        }
        (h.urlInput.parent as? android.view.ViewGroup)?.let { old ->
            old.removeView(h.urlInput); old.removeView(h.btnClear); old.visibility = View.GONE
        }
        h.urlInput.textSize = 16f
        h.urlInput.hint = ""
        // el texto de ayuda se desplaza (tipo anuncio) para leerse completo aunque no quepa
        hintMarquee = ctx.text("", 16f, ctx.col(R.color.ios_secondary)).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1; isSelected = true; isClickable = false; isFocusable = false
            setHorizontallyScrolling(true)
        }
        val fieldBox = android.widget.FrameLayout(ctx)
        fieldBox.addView(h.urlInput, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        fieldBox.addView(hintMarquee, android.widget.FrameLayout.LayoutParams(MATCH, WRAP, android.view.Gravity.CENTER_VERTICAL))
        pillBox.addView(fieldBox, lp(0, ctx.dp(44), 1f))
        pillBox.addView(h.btnClear, lp(ctx.dp(34), ctx.dp(34)))
        filterBtn = ctx.icon(R.drawable.ic_tune, 40, label, 9).apply {
            contentDescription = "Filtros de búsqueda"
            setOnClickListener { it.haptic(); chooseFilter() }
        }
        pillBox.addView(filterBtn)
        searchRow.addView(pillBox, lp(0, ctx.dp(44), 1f))
        searchRow.addView(ctx.icon(R.drawable.ic_search, 44, label, 11).apply {
            contentDescription = "Buscar"
            background = oval(fieldBg)
            setOnClickListener {
                it.haptic()
                if (h.urlInput.text.isNullOrBlank()) {
                    h.urlInput.requestFocus()
                    ctx.getSystemService(InputMethodManager::class.java).showSoftInput(h.urlInput, 0)
                } else analyze()
            }
        }, lp(ctx.dp(44), ctx.dp(44)).apply { marginStart = ctx.dp(8) })
        searchRow.addView(ctx.icon(R.drawable.ic_dots_v, 40, label, 8).apply {
            contentDescription = "Más opciones"
            setOnClickListener { it.haptic(); moreMenu() }
        })
        // Fila 2: las redes
        val top = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        top.addView(searchRow, lp(MATCH, WRAP))
        h.platformBar.background = null
        top.addView(h.platformBar, lp(MATCH, ctx.dp(60)).apply { setMargins(ctx.dp(8), 0, ctx.dp(8), ctx.dp(2)) })
        column.addView(top, 0)

        // Escenario: la lista o la web ocupan todo; el recuadro (buscador, formato, calidad) flota encima
        val stage = android.widget.FrameLayout(ctx)
        val listIdx = column.indexOfChild(b.list)
        column.removeView(b.list)
        column.addView(stage, listIdx, lp(MATCH, 0, 1f))
        stage.addView(b.list, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        web = WebReplica(ctx) { p, url ->
            // Se descarga sin salir de la red: la hoja de guardar aparece encima
            fromWeb = true
            if (p == Platform.SPOTIFY && (url.contains("/playlist/") || url.contains("/album/")) && !DownloadState.running) {
                // la playlist entera (no solo 100): se lee de la página que estás viendo
                web.collectSpotify { title, list ->
                    if (list.size >= 2) presentList(title.ifBlank { "Spotify" }, list) else analyze(url)
                }
            } else analyze(url)
        }
        web.onNav = { updateBack() }
        web.hideField()
        web.view.visibility = View.GONE
        stage.addView(web.view, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        web.onScroll = { y, oy -> if (y <= 0) setOff(0f) else setOff(off + (y - oy)) }

        // El recuadro sale de la lista y pasa a flotar
        (h.root.parent as? android.view.ViewGroup)?.removeView(h.root)
        panelBox = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(ctx.col(R.color.ios_bg)); elevation = ctx.dp(2).toFloat()
            addView(h.root, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        }
        stage.addView(panelBox, android.widget.FrameLayout.LayoutParams(MATCH, WRAP, android.view.Gravity.TOP))
        compactPanel()
        backChip = ctx.text("", 15f, ctx.col(R.color.ios_blue), 1).apply {
            setPadding(ctx.dp(18), ctx.dp(4), ctx.dp(18), ctx.dp(6))
            visibility = View.GONE
            setOnClickListener { it.haptic(); showingResults = false; updateMode() }
        }
        (h.root as android.view.ViewGroup).addView(backChip, 0)

        // Barrita de 2 cm: el recuadro escondido vuelve al tocarla o deslizarla hacia abajo
        pill = android.widget.FrameLayout(ctx).apply {
            visibility = View.GONE
            addView(View(ctx).apply { background = roundRect(0x99FFFFFF.toInt(), ctx.dp(3).toFloat()) },
                android.widget.FrameLayout.LayoutParams(ctx.dp(64), ctx.dp(5), android.view.Gravity.CENTER))
            var downY = 0f
            setOnTouchListener { pv, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> downY = e.rawY
                    android.view.MotionEvent.ACTION_UP -> if (e.rawY - downY > ctx.dp(10) || kotlin.math.abs(e.rawY - downY) < ctx.dp(8)) { pv.haptic(); animateOff(0f) }
                }
                true
            }
        }
        stage.addView(pill, android.widget.FrameLayout.LayoutParams(ctx.dp(160), ctx.dp(26), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))

        // En la lista, un espacio arriba del alto del recuadro
        spacer = View(ctx)
        b.list.adapter = ConcatAdapter(HeaderAdapter(spacer), adapter,
            FooterAdapter({ SettingsActivity.copyHandle(ctx, it) }))
        panelBox.addOnLayoutChangeListener { _, _, t, _, bt, _, _, _, _ ->
            val hgt = bt - t
            if (hgt != panelH) {
                panelH = hgt
                spacer.minimumHeight = hgt
                spacer.requestLayout()
                (web.view.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin = -hgt
                web.view.requestLayout()
                setOff(off)
            }
        }
        b.list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (!rv.canScrollVertically(-1)) setOff(0f) else setOff(off + dy)
            }
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, state: Int) {
                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE && off > 0 && off < panelH)
                    animateOff(if (off > panelH / 3f) panelH.toFloat() else 0f)
            }
        })
        // la barra de descarga deja libre la zona de los botones del sistema
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.bottomBar) { v, ins ->
            val nb = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, ctx.dp(12) + nb); ins
        }
    }

    private fun setOff(v: Float) {
        if (!::panelBox.isInitialized) return
        if (webVisible) {
            // En las redes la página ocupa todo: el formato se elige en la hoja de guardar
            panelBox.visibility = View.GONE; pill.visibility = View.GONE
            web.view.translationY = 0f
            val nb = androidx.core.view.ViewCompat.getRootWindowInsets(b.root)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            val want = ctx.dp(30) + nb
            (web.view.layoutParams as android.widget.FrameLayout.LayoutParams).let { if (it.bottomMargin != want) { it.bottomMargin = want; web.view.requestLayout() } }
            return
        }
        if (panelBox.visibility != View.VISIBLE) panelBox.visibility = View.VISIBLE
        off = v.coerceIn(0f, panelH.toFloat())
        panelBox.translationY = -off
        web.view.translationY = panelH - off
        pill.visibility = if (panelH > 0 && off >= panelH - 1) View.VISIBLE else View.GONE
    }

    private fun animateOff(to: Float) {
        android.animation.ValueAnimator.ofFloat(off, to).apply {
            duration = 220; addUpdateListener { setOff(it.animatedValue as Float) }; start()
        }
    }

    /** Recuadro compacto: solo lo necesario para buscar y elegir formato. */
    private fun compactPanel() {
        h.btnAnalyze.visibility = View.GONE
        fun walk(v: View) {
            if (v is android.widget.TextView && v.text.toString().trim().lowercase() in setOf("formato", "opciones", "calidad", "guardar en")) v.visibility = View.GONE
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(h.root)
        (h.rowQuality.parent as? android.widget.LinearLayout)?.let { row ->
            row.orientation = android.widget.LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            h.rowQuality.minimumHeight = ctx.dp(48); h.rowFolder.minimumHeight = ctx.dp(48)
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c === h.rowQuality || c === h.rowFolder) c.layoutParams = android.widget.LinearLayout.LayoutParams(0, WRAP, 1f)
                else c.visibility = View.GONE
            }
        }
        h.urlInput.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
    }

    /** Todas las redes, YouTube incluido, se navegan con su propia web dentro de la app. */
    private val isWebPlatform get() = true
    private val webVisible get() = ::web.isInitialized && isWebPlatform && !showingResults

    private fun updateMode() {
        if (!::web.isInitialized) return
        val showWeb = webVisible
        b.list.visibility = if (showWeb) View.GONE else View.VISIBLE
        web.view.visibility = if (showWeb) View.VISIBLE else View.GONE
        if (showWeb) { stopPreview(); web.show(prefs.platform); web.resume() } else web.pause()
        backChip.visibility = if (isWebPlatform && showingResults) View.VISIBLE else View.GONE
        backChip.text = "‹ Volver a ${prefs.platform.label}"
        refreshBottomBar()
        setOff(0f)
        if (!isWebPlatform && DownloadState.items.isEmpty() && !homeLoaded && !analyzing && !DownloadState.running) loadHome()
        updateBack()
    }

    /** La flecha de atrás aparece solo cuando hay a dónde volver. */
    private fun updateBack() {
        if (!::backBtn.isInitialized) return
        val can = (webVisible && web.canGoBack()) || (isWebPlatform && showingResults) || (!isWebPlatform && ytQuery != null)
        backBtn.contentDescription = if (can) "Atrás" else "Volver a tu biblioteca"
    }

    private fun goBack() {
        if (!onBack()) (activity as? MainActivity)?.selectTab(1)
        updateBack()
    }

    private fun goHome() {
        if (isWebPlatform) { showingResults = false; updateMode(); web.goHome() }
        else if (!DownloadState.running) { ytQuery = null; h.urlInput.setText(""); loadHome() }
    }

    private fun listen() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "es")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "¿Qué quieres escuchar?")
        try { voice.launch(i) } catch (e: Exception) { toast("Tu celular no tiene búsqueda por voz") }
    }

    private val filters = listOf("Todo", "Canciones", "Videos", "Mixes largos (+20 min)")

    private fun chooseFilter() {
        ActionSheet.show(ctx, "Buscar en YouTube", filters, selected = ytFilter) { i ->
            ytFilter = i
            val q = h.urlInput.text?.toString()?.trim().orEmpty()
            if (q.length >= 2 && Util.extractUrl(q) == null) search(q)
        }
    }

    private fun moreMenu() {
        val p = prefs.platform
        val opts = listOf("Pegar enlace copiado", "Iniciar sesión en ${p.label}", "Ir al inicio de ${p.label}", "Ajustes de descargas")
        ActionSheet.show(ctx, "Opciones", opts) { i ->
            when (i) {
                0 -> {
                    val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
                    val url = Util.extractUrl(clip?.takeIf { c -> c.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString())
                    if (url == null) toast("No hay ningún enlace copiado") else { h.urlInput.setText(url); analyze() }
                }
                1 -> login()
                2 -> goHome()
                3 -> startActivity(Intent(ctx, SettingsActivity::class.java))
            }
        }
    }

    private fun login() {
        val p = prefs.platform
        showingResults = false; updateMode(); web.openLogin(p)
    }

    override fun onBack(): Boolean {
        if (webVisible && web.back()) return true
        if (isWebPlatform && showingResults) { showingResults = false; updateMode(); return true }
        if (!isWebPlatform && ytQuery != null && !DownloadState.running && !analyzing) {
            ytQuery = null; h.urlInput.setText(""); loadHome(); updateBack(); return true
        }
        return false
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) { stopPreview(); if (::web.isInitialized) web.pause() } else { updateMode(); b.root.postDelayed({ offerClipboard() }, 400) }
    }

    /** Inicio de la réplica de YouTube: lo recomendado para ti (si iniciaste sesión) o tendencias. */
    private fun loadHome() {
        homeLoaded = true
        search(null, if (Cookies.has(ctx, Platform.YOUTUBE)) ":ytrec" else null)
    }

    /** Búsqueda propia en YouTube (sin anuncios): solo contenido descargable. */
    private fun search(q: String?, feed: String? = null) {
        if (analyzing || DownloadState.running) return
        if (isWebPlatform) {
            if (q != null) { showingResults = false; updateMode(); web.search(prefs.platform, q) }
            return
        }
        stopPreview()
        analyzing = true
        h.analyzeSpinner.visibility = View.VISIBLE
        h.infoText.visibility = View.VISIBLE
        h.infoText.text = if (q == null) "Cargando recomendaciones…" else "Buscando «$q»…"
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!App.ready.isCompleted) h.infoText.text = "Preparando el motor (solo la primera vez)…"
                App.ready.await()
                fun run(target: String): String {
                    val cookies = Cookies.tempCopy(ctx.applicationContext)
                    try {
                        val req = YoutubeDLRequest(target).addOption("--flat-playlist").addOption("-J").addOption("--no-warnings")
                            .addOption("-I", "1:40")
                            .addOption("--cache-dir", App.cacheDir(ctx.applicationContext).absolutePath)
                        if (cookies != null) req.addOption("--cookies", cookies.absolutePath)
                        return YoutubeDL.getInstance().execute(req).out
                    } finally { cookies?.delete() }
                }
                val trending = "ytsearch40:música éxitos ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}"
                var fellBack = false
                val enc = Uri.encode(q ?: "")
                val json = withContext(Dispatchers.IO) {
                    when {
                        q != null && ytFilter == 1 -> try { run("https://music.youtube.com/search?q=$enc#songs") } catch (e: Exception) { run("ytsearch40:$q audio") }
                        q != null && ytFilter == 2 -> run("https://www.youtube.com/results?search_query=$enc&sp=EgIQAQ%3D%3D")
                        q != null && ytFilter == 3 -> run("https://www.youtube.com/results?search_query=$enc&sp=EgIYAg%3D%3D")
                        q != null -> run("ytsearch40:$q")
                        feed != null -> try { run(feed) } catch (e: Exception) { fellBack = true; run(trending) }
                        else -> run(trending)
                    }
                }
                var list = withContext(Dispatchers.Default) { Util.parseInfo(json, Platform.YOUTUBE).third }
                if (list.isEmpty() && q == null) {
                    fellBack = true
                    val again = withContext(Dispatchers.IO) { run(trending) }
                    list = withContext(Dispatchers.Default) { Util.parseInfo(again, Platform.YOUTUBE).third }
                }
                list.forEach { it.checked = false }
                ytQuery = q; fromSearch = true
                DownloadState.items.clear(); DownloadState.items.addAll(list)
                DownloadState.listTitle = q ?: "Para ti"; DownloadState.isPlaylist = true
                DownloadState.finishedMessage = null; DownloadState.totalCount = 0
                h.infoText.text = when {
                    q != null -> "Resultados para «$q» · toca ▶ para escuchar antes de descargar"
                    feed != null && !fellBack -> "Recomendado para ti · toca ▶ para escuchar"
                    else -> "Lo más escuchado ahora · toca ▶ para escuchar"
                }
                h.listTitle.text = "${list.size} videos"
                adapter.notifyDataSetChanged()
            } catch (e: Throwable) {
                h.infoText.text = "No se pudo buscar: ${(e.message ?: e.toString()).lines().lastOrNull { it.isNotBlank() }?.take(200)}"
            } finally {
                analyzing = false
                h.analyzeSpinner.visibility = View.GONE
                render()
                updateBack()
            }
        }
    }

    // ------------------------------------------------------------------ vista previa
    private fun refreshRow(it: VideoItem) { val i = DownloadState.items.indexOf(it); if (i >= 0) adapter.notifyItemChanged(i) }

    private fun togglePreview(it: VideoItem) {
        if (adapter.previewing === it) { stopPreview(); return }
        stopPreview()
        adapter.previewing = it; adapter.previewLoading = true; refreshRow(it)
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val u = Streams.resolve(ctx.applicationContext, it, false)
                if (adapter.previewing !== it) return@launch
                Preview.play(ctx, u) { b.list.post { if (adapter.previewing === it) stopPreview() } }
                adapter.previewLoading = false; refreshRow(it)
                while (adapter.previewing === it) {
                    adapter.previewFrac = Preview.fraction(); refreshRow(it)
                    kotlinx.coroutines.delay(500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { toast("No se pudo cargar la vista previa"); stopPreview() }
        }
    }

    private fun stopPreview() {
        if (!::adapter.isInitialized) return
        val it = adapter.previewing
        adapter.previewing = null; adapter.previewLoading = false; adapter.previewFrac = 0f
        previewJob?.cancel(); previewJob = null
        Preview.stop()
        if (it != null) refreshRow(it)
    }

    private fun openVideo(it: VideoItem) {
        stopPreview()
        Preview.showVideo(ctx, it.title, { Streams.resolve(ctx.applicationContext, it, true) }, viewLifecycleOwner.lifecycleScope)
    }

    private fun showLoginCard() {
        if (!::h.isInitialized) return
        h.loginCard.visibility =
            if (prefs.platform == Platform.INSTAGRAM && instagramAskedLogin && !Cookies.has(ctx, Platform.INSTAGRAM))
                View.VISIBLE else View.GONE
    }

    private fun showHero() {
        val mode = prefs.speedMode
        h.modeText.text = mode.label
        val n = prefs.statSongs
        h.heroSubtitle.text = if (n > 0) "🎵 $n descargas · ⚡ ${Util.fmtHours(prefs.statSavedSec)}"
                              else "Música y videos sin anuncios"
    }

    // ------------------------------------------------------------------ formato / calidad / carpeta
    private fun setFormat(f: Format) {
        prefs.format = f
        h.segMp3.isSelected = f == Format.MP3
        h.segMp4.isSelected = f == Format.MP4
        showQuality()
        if (::adapter.isInitialized) updateCount()
    }

    private fun showQuality() {
        h.qualityValue.text = if (prefs.format == Format.MP3) Quality.MP3_LABELS[prefs.qualityMp3]
                              else Quality.MP4_LABELS[prefs.qualityMp4]
    }

    private fun chooseQuality() {
        if (DownloadState.running) return
        val mp3 = prefs.format == Format.MP3
        ActionSheet.show(ctx,
            if (mp3) "Calidad del audio MP3" else "Resolución máxima del video MP4",
            if (mp3) Quality.MP3_LABELS else Quality.MP4_LABELS,
            selected = if (mp3) prefs.qualityMp3 else prefs.qualityMp4) { i ->
            if (mp3) prefs.qualityMp3 = i else prefs.qualityMp4 = i
            showQuality()
        }
    }

    private fun loadTargets() {
        viewLifecycleOwner.lifecycleScope.launch {
            val all = Library.dao(ctx).playlists()
            val saved = Prefs2(ctx).lastPlaylists
            targets = saved.filter { id -> all.any { it.id == id } }.toSet()
            targetNames = all.filter { it.id in targets }.joinToString(", ") { it.name }
            showFolder()
        }
    }

    private fun showFolder() {
        h.folderValue.text = targetNames.ifEmpty { "Elegir playlist…" }
    }

    /** Hoja "Guardar en playlist". [confirm] = al tocar Descargar (luego empieza la descarga). */
    private fun pickTargets(confirm: Boolean, title: String? = null) {
        val n = DownloadState.items.count { it.checked }.coerceAtLeast(1)
        val suggested = if (DownloadState.isPlaylist && !fromSearch) DownloadState.listTitle else null
        PlaylistPicker.show(ctx,
            title = title ?: if (confirm) "Se ${if (n == 1) "descargará" else "descargarán"} en la playlist…" else "Guardar en playlist",
            preselected = targets, suggestedNew = suggested,
            okLabel = if (confirm) "Descargar" else "Listo",
            extra = downloadOptions(confirm)) { ids, names ->
            targets = ids; targetNames = names
            Prefs2(ctx).lastPlaylists = ids
            showFolder()
            if (confirm) proceedDownload()
        }
    }

    /** Muestra una lista ya armada (p. ej. la playlist completa de Spotify) y abre la hoja de guardar. */
    private fun presentList(title: String, list: List<VideoItem>) {
        if (DownloadState.running) return
        list.forEach { it.checked = true }
        DownloadState.items.clear(); DownloadState.items.addAll(list)
        DownloadState.listTitle = title; DownloadState.isPlaylist = true
        DownloadState.finishedMessage = null; DownloadState.totalCount = 0
        fromSearch = false
        if (prefs.format != Format.MP3) setFormat(Format.MP3)
        adapter.notifyDataSetChanged()
        render()
        pickTargets(confirm = true, title = "«$title» · ${list.size} canciones")
    }

    /** Arriba de la hoja: formato (MP3/MP4) y "guardar también en el celular". */
    private fun downloadOptions(confirm: Boolean): View {
        val label = ctx.col(R.color.ios_label); val sec = ctx.col(R.color.ios_secondary)
        val p2 = Prefs2(ctx)
        val box = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(8)) }
        if (confirm) {
            val seg = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL; background = roundRect(0x22888888, ctx.dp(12).toFloat()); setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3)) }
            val sp = DownloadState.items.firstOrNull()?.platform == Platform.SPOTIFY
            val opts = listOf(Format.MP3 to "🎵  Audio MP3", Format.MP4 to "🎬  Video MP4")
            val views = ArrayList<android.widget.TextView>()
            fun paint() = views.forEachIndexed { i, v ->
                val on = opts[i].first == prefs.format
                v.background = if (on) roundRect(BRAND_RED, ctx.dp(10).toFloat()) else null
                v.setTextColor(if (on) android.graphics.Color.WHITE else label)
                v.alpha = if (sp && opts[i].first == Format.MP4) 0.35f else 1f
            }
            opts.forEach { (f, t) ->
                val v = ctx.text(t, 15f, label, 1).apply {
                    gravity = android.view.Gravity.CENTER; minHeight = ctx.dp(40)
                    setOnClickListener {
                        if (sp && f == Format.MP4) { toast("De Spotify se descarga solo el audio (MP3)"); return@setOnClickListener }
                        it.haptic(); setFormat(f); paint()
                    }
                }
                views += v; seg.addView(v, lp(0, WRAP, 1f))
            }
            paint()
            box.addView(seg, lp(MATCH, WRAP).apply { bottomMargin = ctx.dp(10) })
        }
        // Guardar también en una carpeta del celular
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            background = roundRect(0x22888888, ctx.dp(12).toFloat()); setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        }
        row.addView(ctx.text("📁", 22f, label), lp(WRAP, WRAP).apply { marginEnd = ctx.dp(10) })
        val texts = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        texts.addView(ctx.text("Guardar también en el celular", 15f, label, 1))
        val where = ctx.text("", 13f, sec)
        texts.addView(where)
        row.addView(texts, lp(0, WRAP, 1f))
        val sw = androidx.appcompat.widget.SwitchCompat(ctx).apply {
            setThumbResource(R.drawable.ios_switch_thumb); setTrackResource(R.drawable.ios_switch_track)
            thumbTintList = null; trackTintList = null; showText = false; isChecked = p2.saveLocal
        }
        row.addView(sw)
        fun paintWhere() {
            where.text = if (sw.isChecked) "En ${ImportActivity.pretty(p2.saveLocalPath)}  ›  cambiar" else "Para verla también en otras apps"
            where.setTextColor(if (sw.isChecked) BRAND_RED else sec)
        }
        paintWhere()
        fun needPerm(): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return false
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return true
        }
        sw.setOnCheckedChangeListener { v, on -> v.haptic(); p2.saveLocal = on; if (on) needPerm(); paintWhere() }
        val pick = View.OnClickListener { v ->
            v.haptic()
            onFolderPicked = { sw.isChecked = true; paintWhere() }
            folderPick.launch(ImportActivity.pickFolder(ctx))
        }
        texts.setOnClickListener(pick)
        row.setOnClickListener(pick)
        box.addView(row, lp(MATCH, WRAP))
        return box
    }

    // ------------------------------------------------------------------ búsqueda (video o playlist)
    private fun analyze(direct: String? = null) {
        if (analyzing || DownloadState.running) { if (direct != null) toast("Espera a que termine la descarga actual"); return }
        if (direct == null) fromWeb = false
        val raw = direct ?: h.urlInput.text?.toString()?.trim().orEmpty()
        val url = Util.extractUrl(raw)
        if (url == null && raw.length >= 2) {
            ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(h.urlInput.windowToken, 0)
            search(raw); return
        }
        if (url == null) {
            h.urlError.text = "Pega un enlace válido (https://…)"
            h.urlError.visibility = View.VISIBLE
            return
        }
        h.urlError.visibility = View.GONE
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(h.urlInput.windowToken, 0)
        // Cambiar de pestaña sola según el enlace pegado.
        val platform = Platform.detect(url) ?: prefs.platform
        if (platform != prefs.platform) setPlatform(platform)

        analyzing = true
        h.analyzeSpinner.visibility = View.VISIBLE
        h.infoText.visibility = View.VISIBLE
        h.infoText.text = "Buscando…"
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!App.ready.isCompleted) h.infoText.text = "Preparando el motor (solo la primera vez)…"
                App.ready.await()
                if (Updater.ensureFresh(ctx.applicationContext, App.UPDATE_EVERY_MS, 30_000) != null) {
                    h.infoText.text = "yt-dlp actualizado. Buscando…"
                } else h.infoText.text = "Buscando…"
                val spotifyResult = if (platform == Platform.SPOTIFY)
                    withContext(Dispatchers.IO) { SpotifySource.fetch(url) } else null
                val json = if (spotifyResult != null) "" else withContext(Dispatchers.IO) {
                    fun info(fast: Boolean): String {
                        val cookies = Cookies.tempCopy(ctx.applicationContext)
                        try {
                            val req = YoutubeDLRequest(url)
                                .addOption("--flat-playlist")
                                .addOption("-J")
                                .addOption("--no-warnings")
                            if (cookies != null) req.addOption("--cookies", cookies.absolutePath)
                            req.addOption("--cache-dir", App.cacheDir(ctx.applicationContext).absolutePath)
                            // Solo necesitamos título y duración: sin resolver el JS de YouTube (mucho más rápido).
                            if (fast && platform == Platform.YOUTUBE)
                                req.addOption("--extractor-args", "youtube:player_client=visionos;player_skip=configs,js")
                            return YoutubeDL.getInstance().execute(req).out
                        } finally { cookies?.delete() }
                    }
                    try { info(fast = true) } catch (e: YoutubeDL.CanceledException) { throw e }
                    catch (e: Exception) {
                        if (Util.isBotCheck(e.message) || platform != Platform.YOUTUBE) throw e else info(fast = false)
                    }
                }
                val (title, isPlaylist, list) = spotifyResult ?: withContext(Dispatchers.Default) { Util.parseInfo(json, platform) }
                DownloadState.items.clear()
                DownloadState.items.addAll(list)
                fromSearch = false
                DownloadState.listTitle = title
                DownloadState.isPlaylist = isPlaylist
                DownloadState.finishedMessage = null
                DownloadState.totalCount = 0
                if (list.isEmpty()) throw IllegalStateException("ERROR: no se encontraron videos en ese enlace")
                // Spotify da solo 100 por enlace: se abre la playlist en la pestaña Spotify y se lee completa
                if (platform == Platform.SPOTIFY && isPlaylist && list.size >= SpotifySource.MAX_TRACKS) {
                    if (prefs.platform != Platform.SPOTIFY) setPlatform(Platform.SPOTIFY)
                    showingResults = false; updateMode()
                    toast("Tu playlist tiene más de 100: leyendo la lista completa…")
                    web.openAndCollect(url) { t, l -> presentList(if (l.size > list.size) t.ifBlank { title } else title, if (l.size > list.size) l else list) }
                    return@launch
                }
                h.infoText.text = when {
                    !isPlaylist -> ""
                    platform == Platform.SPOTIFY && list.size >= SpotifySource.MAX_TRACKS ->
                        "Spotify «$title»: se muestran las primeras ${SpotifySource.MAX_TRACKS} canciones (límite de Spotify)."
                    platform == Platform.SPOTIFY -> "Spotify «$title» · cada canción se buscará en YouTube"
                    else -> "Playlist «$title»"
                }
                h.infoText.visibility = if (isPlaylist) View.VISIBLE else View.GONE
                h.listTitle.text = if (isPlaylist) "${list.size} ${noun()}" else noun(1)
                adapter.notifyDataSetChanged()
                if (fromWeb || isWebPlatform) {
                    // todo marcado y la hoja de guardar encima (sin salir de la red)
                    list.forEach { it.checked = true }
                    pickTargets(confirm = true, title = if (isPlaylist) "«$title» · ${list.size} ${noun()}" else list.first().title)
                } else b.downloadFrame.postDelayed({ b.downloadFrame.bounce() }, 150)
            } catch (e: Throwable) {
                val login = platform != Platform.YOUTUBE && Util.needsLogin(e.message)
                if (login && platform == Platform.INSTAGRAM) { instagramAskedLogin = true; showLoginCard() }
                if (Util.isBotCheck(e.message) || login) {
                    val (info, sheet, action) = when (platform) {
                        Platform.INSTAGRAM -> Triple("Instagram pide iniciar sesión para ver este contenido.",
                            "Instagram pide iniciar sesión. Hazlo una vez y la app lo recordará (te recomendamos una cuenta secundaria).",
                            "Iniciar sesión en Instagram")
                        Platform.SPOTIFY -> Triple("YouTube pide verificar que no eres un robot.",
                            "YouTube pide confirmar que no eres un robot. Resuélvelo una vez y la app lo recordará.", "Verificar ahora")
                        Platform.FACEBOOK -> Triple("Facebook pide iniciar sesión para ver este video.",
                            "Este video de Facebook no es público o Facebook pide iniciar sesión. Hazlo una vez y la app lo recordará.",
                            "Iniciar sesión en Facebook")
                        Platform.TIKTOK -> Triple("TikTok pide verificación.",
                            "TikTok pide verificar. Resuélvelo una vez y la app lo recordará.", "Verificar ahora")
                        Platform.YOUTUBE -> Triple("YouTube pide verificar que no eres un robot.",
                            "YouTube pide confirmar que no eres un robot. Resuélvelo una vez y la app lo recordará.", "Verificar ahora")
                    }
                    h.infoText.text = info
                    ActionSheet.show(ctx, sheet, listOf(action)) {
                        analyzeAfterVerify = true
                        openVerify(platform)
                    }
                } else {
                    val msg = e.message?.lines()?.lastOrNull { it.contains("ERROR") }
                        ?.removePrefix("ERROR:")?.trim() ?: e.message ?: e.toString()
                    h.infoText.text = "No se pudo leer el enlace: ${msg.take(300)}"
                }
            } finally {
                analyzing = false
                h.analyzeSpinner.visibility = View.GONE
                render()
            }
        }
    }

    // ------------------------------------------------------------------ descarga
    private fun startDownload() {
        if (DownloadState.running) return
        if (DownloadState.items.size == 1) DownloadState.items[0].checked = true
        if (DownloadState.items.none { it.checked }) { toast("Selecciona al menos un video"); return }
        pickTargets(confirm = true)
    }

    private fun proceedDownload() {
        if (DownloadState.running) return
        // Primera vez: pedir permiso para seguir con la pantalla bloqueada.
        if (!prefs.batteryPromptShown && !Device.ignoresBatteryOptimizations(ctx)) {
            prefs.batteryPromptShown = true
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Descargar con la pantalla bloqueada")
                .setMessage("Para que las descargas sigan aunque bloquees el celular, " +
                    "permite que DownSong funcione sin restricciones de batería.")
                .setPositiveButton("Permitir") { _, _ ->
                    SettingsActivity.requestBatteryExemption(ctx); launchService()
                }
                .setNegativeButton("Ahora no") { _, _ -> launchService() }
                .show()
            return
        }
        launchService()
    }

    private fun launchService() {
        val q = if (prefs.format == Format.MP3) prefs.qualityMp3 else prefs.qualityMp4
        val p2 = Prefs2(ctx)
        DownloadService.start(ctx, prefs.format, q, targets.toLongArray(), if (p2.saveLocal) p2.saveLocalPath else null)
    }

    private fun confirmCancel() {
        ActionSheet.show(ctx, "¿Cancelar la descarga? Los archivos ya guardados se conservan.",
            listOf("Cancelar descarga"), destructive = setOf(0)) {
            DownloadService.cancel(ctx)
        }
    }

    /** "videos" o "canciones" (Spotify). */
    private fun noun(n: Int = 2): String {
        val sp = DownloadState.items.firstOrNull()?.platform == Platform.SPOTIFY
        return if (n == 1) (if (sp) "Canción" else "Video") else (if (sp) "canciones" else "videos")
    }

    private fun selectedCount(): Int =
        if (DownloadState.items.size == 1) 1 else DownloadState.items.count { it.checked }

    /** El botón Descargar solo aparece cuando hay algo marcado (o una descarga en curso). */
    private fun refreshBottomBar() {
        if (!::web.isInitialized) return
        val s = DownloadState
        val show = s.running || s.finishedMessage != null || (!webVisible && selectedCount() > 0)
        val target = if (show) View.VISIBLE else View.GONE
        if (b.bottomBar.visibility != target) {
            b.bottomBar.visibility = target
            if (show) { b.bottomBar.translationY = b.bottomBar.height.toFloat(); b.bottomBar.animate().translationY(0f).setDuration(220).start() }
        }
    }

    private fun updateCount() {
        val sel = selectedCount()
        val total = DownloadState.items.size
        h.btnSelectAll.text = if (total > 0 && DownloadState.items.all { it.checked }) "Quitar todo" else "Seleccionar todo"
        h.btnSelectAll.visibility = if (total > 1 && !DownloadState.running) View.VISIBLE else View.GONE
        if (total > 1) h.listTitle.text = "$total ${noun()} · $sel seleccionados"
        val ext = prefs.format.ext.uppercase(Locale.ROOT)
        b.btnDownload.isEnabled = !DownloadState.running && !analyzing && sel > 0
        b.btnDownload.text = when {
            DownloadState.running -> "Descargando…"
            total == 0 -> "Descargar"
            sel == 0 -> "Selecciona ${noun()}"
            sel > 1 -> "⬇  Descargar $sel en $ext"
            else -> "⬇  Descargar $ext"
        }
        refreshBottomBar()
    }

    private fun render() {
        val s = DownloadState
        val running = s.running
        refreshBottomBar()
        h.listHeader.visibility = if (s.items.isNotEmpty()) View.VISIBLE else View.GONE
        h.btnAnalyze.isEnabled = !running && !analyzing
        h.btnPaste.isEnabled = !running && !analyzing
        h.urlInput.isEnabled = !running
        h.btnClear.visibility = if (running || h.urlInput.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        h.rowFolder.isEnabled = !running
        h.rowQuality.isEnabled = !running
        h.segMp3.isEnabled = !running
        h.segMp4.isEnabled = !running
        h.segment.alpha = if (running) 0.6f else 1f
        b.btnCancel.visibility = if (running) View.VISIBLE else View.GONE
        b.downloadFrame.shimmering = running
        heroView?.animating = !running
        b.verifyBanner.visibility = if (running && s.needsVerification) View.VISIBLE else View.GONE
        if (s.needsVerification) {
            b.verifyTitle.text = when (s.verifyPlatform) {
                Platform.INSTAGRAM -> "Instagram pide iniciar sesión"
                else -> "${s.verifyPlatform.label} pide verificar"
            }
            b.verifyText.text = if (s.verifyPlatform == Platform.INSTAGRAM)
                "Descargas en pausa. Toca para iniciar sesión y continuar."
                else "Descargas en pausa. Toca para confirmar que no eres un robot."
        }
        updateCount()

        if (adapter.locked != running || lastRenderedRunning != running) {
            adapter.locked = running
            lastRenderedRunning = running
            lastActive.clear()
            adapter.notifyDataSetChanged()
        } else {
            // refrescar las filas activas y las que acaban de terminar
            val now = s.active.toSet()
            (now + lastActive).forEach { item ->
                val pos = s.items.indexOf(item)
                if (pos >= 0) adapter.notifyItemChanged(pos)
            }
            lastActive.clear(); lastActive.addAll(now)
        }

        if (running || s.finishedMessage != null) {
            b.progressCard.visibility = View.VISIBLE
            val finished = s.doneCount + s.errorCount
            if (running) {
                b.currentText.text = when {
                    s.phase.isNotEmpty() -> s.phase
                    s.active.size > 1 -> "Descargando ${s.active.size} a la vez"
                    else -> s.active.firstOrNull()?.title ?: "Preparando…"
                }
                b.modeBadge.visibility = if (s.mode == SpeedMode.ULTRA) View.VISIBLE else View.GONE
                b.speedBox.visibility = View.VISIBLE
                b.sparkline.values = s.speedHistory.toList()
                b.speedText.text = if (s.perMinute > 0) String.format(Locale.US, "%.1f", s.perMinute) else "—"
                b.parallelText.text = "descargas/min"
                b.etaText.text = "Quedan ${Util.fmtRemaining(s.totalEtaSec)}  ·  ${s.active.size} activas de ${s.concurrency}" +
                    (if (s.speedBps > 0) "  ·  ${Util.fmtSpeed(s.speedBps)}" else "")
                b.noticeText.text = s.notice
                b.noticeText.visibility = if (s.notice != null) View.VISIBLE else View.GONE
            } else {
                b.currentText.text = s.finishedMessage
                b.modeBadge.visibility = View.GONE
                b.speedBox.visibility = View.GONE
                b.noticeText.visibility = View.GONE
                b.etaText.text = if (s.errorCount > 0) "Toca «Descargar» otra vez para reintentar los que fallaron."
                                 else "Guardado en tu biblioteca: ${targetNames.ifEmpty { "Descargas" }}"
            }
            val inFlight = if (running) s.active.sumOf { it.progress.toDouble() } / 100.0 else 0.0
            val frac = if (s.totalCount > 0) ((finished + inFlight) / s.totalCount).toFloat().coerceIn(0f, 1f) else 0f
            b.totalText.text = "$finished de ${s.totalCount} listos" +
                (if (s.errorCount > 0) "  ·  ${s.errorCount} con error" else "")
            b.totalPct.text = "${(frac * 100).toInt()} %"
            b.progressTotal.setProgressCompat((frac * 1000).toInt(), true)
        } else {
            b.progressCard.visibility = View.GONE
        }

        if (!running && s.finishedMessage != null && hideDoneAt == 0L) {
            hideDoneAt = System.currentTimeMillis()
            b.progressCard.postDelayed(hideDone, if (s.errorCount > 0) 15_000 else 6_000)
        }
        if (running) { hideDoneAt = 0L; b.progressCard.removeCallbacks(hideDone) }
        if (s.celebrate) {
            s.celebrate = false
            b.confetti.burst()
            b.root.haptic(true)
            showHero()
            (activity as? MainActivity)?.refreshLibrary()
        }
    }

    private var hideDoneAt = 0L
    private val hideDone = Runnable { dismissDone() }

    /** Quita la tarjeta de descargas terminadas. */
    private fun dismissDone() {
        if (DownloadState.running || !isAdded) return
        DownloadState.finishedMessage = null
        hideDoneAt = 0L
        render()
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
}
