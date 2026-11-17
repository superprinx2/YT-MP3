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

class DownloadFragment : Fragment() {

    private val ctx get() = requireActivity() as AppCompatActivity
    private var targets: Set<Long> = emptySet()
    private var targetNames: String = ""


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
        adapter = VideoAdapter(DownloadState.items) { updateCount() }
        b.list.adapter = ConcatAdapter(HeaderAdapter(h.root), adapter,
            FooterAdapter({ SettingsActivity.copyHandle(ctx, it) }, { it.haptic(); Donate.show(ctx) }))
        b.list.itemAnimator = null

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
                v.setOnClickListener { if (!DownloadState.running) { it.haptic(); setPlatform(p) } }
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
    }

    override fun onPause() {
        h.btnDonate.removeCallbacks(donateWiggle)
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
        h.urlInput.hint = when (p) {
            Platform.YOUTUBE -> "Pega el enlace del video o playlist"
            Platform.TIKTOK -> "Pega el enlace del TikTok"
            Platform.INSTAGRAM -> "Pega el enlace del reel o publicación"
            Platform.FACEBOOK -> "Pega el enlace del video o reel de Facebook"
            Platform.SPOTIFY -> "Pega el enlace de la playlist, álbum o canción"
        }
        if (DownloadState.items.isEmpty() && !analyzing) {
            h.infoText.visibility = View.VISIBLE
            h.infoText.text = when (p) {
                Platform.YOUTUBE -> "También puedes tocar «Compartir» en la app de YouTube y elegir DownSong."
                Platform.TIKTOK -> "Videos sin marca de agua en MP4, o solo el audio en MP3. También funciona «Compartir» desde TikTok."
                Platform.INSTAGRAM -> "Reels y videos de publicaciones en MP4 o MP3. También funciona «Compartir» desde Instagram. " +
                    "Si Instagram pidiera iniciar sesión, te avisaremos."
                Platform.FACEBOOK -> "Videos y reels públicos de Facebook en MP4 o MP3. También funciona «Compartir» desde Facebook."
                Platform.SPOTIFY -> "Playlists, álbumes y canciones de Spotify en MP3: la app lee la lista y baja cada canción " +
                    "desde YouTube (Spotify no permite bajar su audio). Hasta ${SpotifySource.MAX_TRACKS} canciones por enlace."
            }
        }
        // Spotify: solo MP3
        val sp = p == Platform.SPOTIFY
        if (sp && prefs.format != Format.MP3) setFormat(Format.MP3)
        h.segMp4.isEnabled = !sp && !DownloadState.running
        h.segMp4.alpha = if (sp) 0.35f else 1f
        showLoginCard()
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
    private fun pickTargets(confirm: Boolean) {
        val n = DownloadState.items.count { it.checked }.coerceAtLeast(1)
        val suggested = if (DownloadState.isPlaylist) DownloadState.listTitle else null
        PlaylistPicker.show(ctx,
            title = if (confirm) "Se ${if (n == 1) "descargará" else "descargarán"} en la playlist…" else "Guardar en playlist",
            preselected = targets, suggestedNew = suggested,
            okLabel = if (confirm) "Descargar" else "Listo") { ids, names ->
            targets = ids; targetNames = names
            Prefs2(ctx).lastPlaylists = ids
            showFolder()
            if (confirm) proceedDownload()
        }
    }

    // ------------------------------------------------------------------ búsqueda (video o playlist)
    private fun analyze() {
        if (analyzing || DownloadState.running) return
        val url = Util.extractUrl(h.urlInput.text?.toString())
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
                DownloadState.listTitle = title
                DownloadState.isPlaylist = isPlaylist
                DownloadState.finishedMessage = null
                DownloadState.totalCount = 0
                if (list.isEmpty()) throw IllegalStateException("ERROR: no se encontraron videos en ese enlace")
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
                b.downloadFrame.postDelayed({ b.downloadFrame.bounce() }, 150)
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
        DownloadService.start(ctx, prefs.format, q, targets.toLongArray())
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
    }

    private fun render() {
        val s = DownloadState
        val running = s.running
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

        if (s.celebrate) {
            s.celebrate = false
            b.confetti.burst()
            b.root.haptic(true)
            showHero()
            (activity as? MainActivity)?.refreshLibrary()
        }
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
}
