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

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var h: HeaderMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: VideoAdapter
    private var analyzing = false
    private var startAfterPick = false
    private var analyzeAfterVerify = false
    private var lastRenderedRunning = false
    private val lastActive = HashSet<VideoItem>()
    private val heroView: HeroView? get() = if (::h.isInitialized) h.hero.getChildAt(0) as? HeroView else null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { startAfterPick = false; return@registerForActivityResult }
        contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.treeUri = uri
        showFolder()
        if (startAfterPick) { startAfterPick = false; startDownload() }
    }

    private val verify = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        if (DownloadState.running) DownloadService.verificationDone()
        else if (analyzeAfterVerify) { analyzeAfterVerify = false; analyze() }
    }

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val stateListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.list.layoutManager = LinearLayoutManager(this)
        h = HeaderMainBinding.inflate(layoutInflater, b.list, false)
        adapter = VideoAdapter(DownloadState.items) { updateCount() }
        b.list.adapter = ConcatAdapter(HeaderAdapter(h.root), adapter,
            FooterAdapter { SettingsActivity.copyHandle(this, it) })
        b.list.itemAnimator = null

        // --- interacción
        listOf(h.btnAnalyze, h.btnPaste, h.btnSettings, h.modeChip, h.rowFolder, h.rowQuality, b.btnCancel)
            .forEach { it.pressable(0.96f) }
        h.logo.setOnClickListener { it.haptic(); it.bounce() }
        h.btnPaste.setOnClickListener {
            it.haptic()
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { c -> c.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
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
        val openSettings = View.OnClickListener { v -> v.haptic(); startActivity(Intent(this, SettingsActivity::class.java)) }
        h.btnSettings.setOnClickListener(openSettings)
        h.modeChip.setOnClickListener(openSettings)
        h.rowFolder.setOnClickListener { it.haptic(); pickFolder.launch(prefs.treeUri) }
        h.rowQuality.setOnClickListener { it.haptic(); chooseQuality() }
        h.segMp3.setOnClickListener { if (!DownloadState.running) { it.haptic(); setFormat(Format.MP3) } }
        h.segMp4.setOnClickListener { if (!DownloadState.running) { it.haptic(); setFormat(Format.MP4) } }
        b.btnDownload.setOnClickListener { it.haptic(true); startDownload() }
        pressScale(b.btnDownload, b.downloadFrame)
        b.btnCancel.setOnClickListener { it.haptic(); confirmCancel() }
        b.verifyBanner.setOnClickListener { it.haptic(); openVerify() }

        setFormat(prefs.format)
        showFolder()

        // Restaurar textos si la pantalla se recrea (giro, modo oscuro…)
        if (DownloadState.items.isNotEmpty()) {
            h.listTitle.text = if (DownloadState.isPlaylist) "${DownloadState.items.size} videos" else "Video"
            h.infoText.text = if (DownloadState.isPlaylist) "Playlist «${DownloadState.listTitle}»" else ""
            h.infoText.visibility = if (DownloadState.isPlaylist) View.VISIBLE else View.GONE
        }

        if (savedInstanceState == null) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
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

    override fun onStop() {
        DownloadState.removeListener(stateListener)
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(DownloadService.EXTRA_OPEN_VERIFY, false) == true) {
            intent.removeExtra(DownloadService.EXTRA_OPEN_VERIFY)
            openVerify()
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val url = Util.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        if (DownloadState.running) { toast("Hay una descarga en curso"); return }
        h.urlInput.setText(url)
        analyze()
    }

    private fun openVerify() = verify.launch(Intent(this, VerifyActivity::class.java))

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
        ActionSheet.show(this,
            if (mp3) "Calidad del audio MP3" else "Resolución máxima del video MP4",
            if (mp3) Quality.MP3_LABELS else Quality.MP4_LABELS,
            selected = if (mp3) prefs.qualityMp3 else prefs.qualityMp4) { i ->
            if (mp3) prefs.qualityMp3 = i else prefs.qualityMp4 = i
            showQuality()
        }
    }

    private fun validTree(): Uri? {
        val uri = prefs.treeUri ?: return null
        val granted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        val doc = DocumentFile.fromTreeUri(this, uri)
        return if (granted && doc != null && doc.exists()) uri else null
    }

    private fun showFolder() {
        val uri = validTree()
        h.folderValue.text = if (uri == null) "Elegir…" else Util.prettyTree(uri)
            .replace("Almacenamiento interno", "Teléfono")
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
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(h.urlInput.windowToken, 0)

        analyzing = true
        h.analyzeSpinner.visibility = View.VISIBLE
        h.infoText.visibility = View.VISIBLE
        h.infoText.text = "Buscando…"
        render()

        lifecycleScope.launch {
            try {
                if (!App.ready.isCompleted) h.infoText.text = "Preparando el motor (solo la primera vez)…"
                App.ready.await()
                if (Updater.ensureFresh(applicationContext, App.UPDATE_EVERY_MS, 30_000) != null) {
                    h.infoText.text = "yt-dlp actualizado. Buscando…"
                } else h.infoText.text = "Buscando…"
                val json = withContext(Dispatchers.IO) {
                    fun info(fast: Boolean): String {
                        val cookies = Cookies.tempCopy(applicationContext)
                        try {
                            val req = YoutubeDLRequest(url)
                                .addOption("--flat-playlist")
                                .addOption("-J")
                                .addOption("--no-warnings")
                            if (cookies != null) req.addOption("--cookies", cookies.absolutePath)
                            req.addOption("--cache-dir", App.cacheDir(applicationContext).absolutePath)
                            // Solo necesitamos título y duración: sin resolver el JS de YouTube (mucho más rápido).
                            if (fast) req.addOption("--extractor-args", "youtube:player_client=visionos;player_skip=configs,js")
                            return YoutubeDL.getInstance().execute(req).out
                        } finally { cookies?.delete() }
                    }
                    try { info(fast = true) } catch (e: YoutubeDL.CanceledException) { throw e }
                    catch (e: Exception) { if (Util.isBotCheck(e.message)) throw e else info(fast = false) }
                }
                val (title, isPlaylist, list) = withContext(Dispatchers.Default) { Util.parseInfo(json) }
                DownloadState.items.clear()
                DownloadState.items.addAll(list)
                DownloadState.listTitle = title
                DownloadState.isPlaylist = isPlaylist
                DownloadState.finishedMessage = null
                DownloadState.totalCount = 0
                h.infoText.text = if (isPlaylist) "Playlist «$title»" else ""
                h.infoText.visibility = if (isPlaylist) View.VISIBLE else View.GONE
                h.listTitle.text = if (isPlaylist) "${list.size} videos" else "Video"
                adapter.notifyDataSetChanged()
                b.downloadFrame.postDelayed({ b.downloadFrame.bounce() }, 150)
            } catch (e: Throwable) {
                if (Util.isBotCheck(e.message)) {
                    h.infoText.text = "YouTube pide verificar que no eres un robot."
                    ActionSheet.show(this@MainActivity,
                        "YouTube pide confirmar que no eres un robot. Resuélvelo una vez y la app lo recordará.",
                        listOf("Verificar ahora")) {
                        analyzeAfterVerify = true
                        openVerify()
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
        val tree = validTree()
        if (tree == null) {
            toast("Elige la carpeta donde guardar")
            startAfterPick = true
            pickFolder.launch(prefs.treeUri)
            return
        }
        // Primera vez: pedir permiso para seguir con la pantalla bloqueada.
        if (!prefs.batteryPromptShown && !Device.ignoresBatteryOptimizations(this)) {
            prefs.batteryPromptShown = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Descargar con la pantalla bloqueada")
                .setMessage("Para que las descargas sigan aunque bloquees el celular, " +
                    "permite que YT MP3 funcione sin restricciones de batería.")
                .setPositiveButton("Permitir") { _, _ ->
                    SettingsActivity.requestBatteryExemption(this); launchService(tree)
                }
                .setNegativeButton("Ahora no") { _, _ -> launchService(tree) }
                .show()
            return
        }
        launchService(tree)
    }

    private fun launchService(tree: Uri) {
        val q = if (prefs.format == Format.MP3) prefs.qualityMp3 else prefs.qualityMp4
        DownloadService.start(this, prefs.format, q, tree)
    }

    private fun confirmCancel() {
        ActionSheet.show(this, "¿Cancelar la descarga? Los archivos ya guardados se conservan.",
            listOf("Cancelar descarga"), destructive = setOf(0)) {
            DownloadService.cancel(this)
        }
    }

    private fun selectedCount(): Int =
        if (DownloadState.items.size == 1) 1 else DownloadState.items.count { it.checked }

    private fun updateCount() {
        val sel = selectedCount()
        val total = DownloadState.items.size
        h.btnSelectAll.text = if (total > 0 && DownloadState.items.all { it.checked }) "Quitar todo" else "Seleccionar todo"
        h.btnSelectAll.visibility = if (total > 1 && !DownloadState.running) View.VISIBLE else View.GONE
        if (total > 1) h.listTitle.text = "$total videos · $sel seleccionados"
        val ext = prefs.format.ext.uppercase(Locale.ROOT)
        b.btnDownload.isEnabled = !DownloadState.running && !analyzing && sel > 0
        b.btnDownload.text = when {
            DownloadState.running -> "Descargando…"
            total == 0 -> "Descargar"
            sel == 0 -> "Selecciona videos"
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
        h.rowFolder.isEnabled = !running
        h.rowQuality.isEnabled = !running
        h.segMp3.isEnabled = !running
        h.segMp4.isEnabled = !running
        h.segment.alpha = if (running) 0.6f else 1f
        b.btnCancel.visibility = if (running) View.VISIBLE else View.GONE
        b.downloadFrame.shimmering = running
        heroView?.animating = !running
        b.verifyBanner.visibility = if (running && s.needsVerification) View.VISIBLE else View.GONE
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
                                 else "Los archivos están en: ${h.folderValue.text}"
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
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
