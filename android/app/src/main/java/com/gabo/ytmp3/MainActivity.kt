package com.gabo.ytmp3

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
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
    private var lastRenderedRunning = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { startAfterPick = false; return@registerForActivityResult }
        contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.treeUri = uri
        showFolder()
        if (startAfterPick) { startAfterPick = false; startDownload() }
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
        b.list.adapter = ConcatAdapter(HeaderAdapter(h.root), adapter)
        b.list.itemAnimator = null

        h.btnPaste.setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            val url = Util.extractUrl(text)
            if (url == null) toast("No hay ningún enlace copiado")
            else { h.urlInput.setText(url); analyze() }
        }
        h.btnAnalyze.setOnClickListener { analyze() }
        h.urlInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { analyze(); true } else false
        }
        h.btnSelectAll.setOnClickListener {
            val all = DownloadState.items.all { it.checked }
            DownloadState.items.forEach { it.checked = !all }
            adapter.notifyItemRangeChanged(0, DownloadState.items.size)
            updateCount()
        }
        h.btnMore.setOnClickListener { moreMenu() }
        h.rowFolder.setOnClickListener { pickFolder.launch(prefs.treeUri) }
        h.rowQuality.setOnClickListener { chooseQuality() }
        h.segMp3.setOnClickListener { setFormat(Format.MP3) }
        h.segMp4.setOnClickListener { setFormat(Format.MP4) }
        b.btnDownload.setOnClickListener { startDownload() }
        b.btnCancel.setOnClickListener { confirmCancel() }

        setFormat(prefs.format)
        showFolder()

        // Restaurar textos si la pantalla se recrea (giro, modo oscuro…)
        if (DownloadState.items.isNotEmpty()) {
            h.listTitle.text = if (DownloadState.isPlaylist) "${DownloadState.items.size} videos" else "Video"
            h.infoText.text = if (DownloadState.isPlaylist) "Playlist «${DownloadState.listTitle}»" else ""
            h.infoText.visibility = if (DownloadState.isPlaylist) View.VISIBLE else View.GONE
        }

        if (savedInstanceState == null) handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        DownloadState.addListener(stateListener)
        render()
    }

    override fun onStop() {
        DownloadState.removeListener(stateListener)
        super.onStop()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val url = Util.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        if (DownloadState.running) { toast("Hay una descarga en curso"); return }
        h.urlInput.setText(url)
        analyze()
    }

    // ------------------------------------------------------------------ formato / calidad / carpeta
    private fun setFormat(f: Format) {
        if (DownloadState.running) return
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
                        val req = YoutubeDLRequest(url)
                            .addOption("--flat-playlist")
                            .addOption("-J")
                            .addOption("--no-warnings")
                        // Solo necesitamos título y duración: sin resolver el JS de YouTube (mucho más rápido).
                        if (fast) req.addOption("--extractor-args", "youtube:player_client=visionos;player_skip=configs,js")
                        return YoutubeDL.getInstance().execute(req).out
                    }
                    try { info(fast = true) } catch (e: YoutubeDL.CanceledException) { throw e }
                    catch (e: Exception) { info(fast = false) }
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
            } catch (e: Throwable) {
                val msg = e.message?.lines()?.lastOrNull { it.contains("ERROR") }
                    ?.removePrefix("ERROR:")?.trim() ?: e.message ?: e.toString()
                h.infoText.visibility = View.VISIBLE
                h.infoText.text = "No se pudo leer el enlace: ${msg.take(300)}"
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
        if (total > 1) h.listTitle.text = "${total} videos · $sel seleccionados"
        val ext = prefs.format.ext.uppercase(Locale.ROOT)
        b.btnDownload.isEnabled = !DownloadState.running && !analyzing && sel > 0
        b.btnDownload.text = when {
            DownloadState.running -> "Descargando…"
            total == 0 -> "Descargar"
            sel == 0 -> "Selecciona videos"
            sel > 1 -> "Descargar $sel en $ext"
            else -> "Descargar $ext"
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
        updateCount()

        if (adapter.locked != running || lastRenderedRunning != running) {
            adapter.locked = running
            lastRenderedRunning = running
            adapter.notifyDataSetChanged()
        } else {
            s.current?.let { cur ->
                val pos = s.items.indexOf(cur)
                if (pos >= 0) adapter.notifyItemChanged(pos)
            }
        }

        if (running || s.finishedMessage != null) {
            b.progressCard.visibility = View.VISIBLE
            if (running) {
                b.currentText.text = s.current?.title ?: s.phase
                b.progressCurrent.setProgressCompat((s.progress * 10).toInt(), true)
                b.etaText.text = if (s.phase == "Descargando") {
                    String.format(Locale.US, "%.1f %%  ·  quedan %s", s.progress, Util.fmtEta(s.etaSec)) +
                        if (s.speed.isNotEmpty()) "  ·  ${s.speed}" else ""
                } else s.phase
            } else {
                b.currentText.text = s.finishedMessage
                b.progressCurrent.setProgressCompat(1000, false)
                b.etaText.text = "Toca «Descargar» otra vez para reintentar los que fallaron."
                    .takeIf { s.errorCount > 0 } ?: "Los archivos están en: ${h.folderValue.text}"
            }
            val finished = s.doneCount + s.errorCount
            val frac = if (s.totalCount > 0)
                (finished + if (running) s.progress / 100f else 0f) / s.totalCount else 0f
            b.totalText.text = "$finished de ${s.totalCount} listos" +
                (if (s.errorCount > 0) "  ·  ${s.errorCount} con error" else "")
            b.totalPct.text = "${(frac * 100).toInt()} %"
            b.progressTotal.setProgressCompat((frac * 1000).toInt(), true)
        } else {
            b.progressCard.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ menú "⋯"
    private fun moreMenu() {
        ActionSheet.show(this, "yt-dlp ${Updater.currentVersion(this)}",
            listOf("Actualizar yt-dlp", "Acerca de")) { i ->
            when (i) {
                0 -> updateYtDlp()
                1 -> about()
            }
        }
    }

    private fun updateYtDlp() {
        if (DownloadState.running) { toast("Espera a que termine la descarga"); return }
        toast("Buscando actualización…")
        lifecycleScope.launch {
            val msg = try {
                App.ready.await()
                val v = Updater.updateNow(applicationContext)
                if (v == null) "yt-dlp ya está al día (${Updater.currentVersion(applicationContext)})"
                else "yt-dlp actualizado a $v"
            } catch (e: Throwable) {
                "No se pudo actualizar: ${e.message}"
            }
            toast(msg)
        }
    }

    private fun about() {
        val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        MaterialAlertDialogBuilder(this)
            .setTitle("YT MP3 $v")
            .setMessage("yt-dlp ${Updater.currentVersion(this)}\n\n" +
                "Descarga audio (MP3) o video (MP4) de YouTube usando yt-dlp y FFmpeg " +
                "directamente en el teléfono, sin anuncios.\n\n" +
                "La app se actualiza sola cada pocas horas. Si un video falla, usa «Actualizar yt-dlp».\n\n" +
                "Fuente Inter © Rasmus Andersson (SIL OFL).")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
