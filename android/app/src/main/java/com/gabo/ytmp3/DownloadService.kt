package com.gabo.ytmp3

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class DownloadService : Service() {

    companion object {
        private const val TAG = "YTMP3"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_TREE = "tree"

        @Volatile private var cancelRequested = false
        @Volatile private var currentPid: String? = null

        fun start(ctx: Context, format: Format, quality: Int, tree: Uri) {
            val i = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FORMAT, format.name)
                .putExtra(EXTRA_QUALITY, quality)
                .putExtra(EXTRA_TREE, tree.toString())
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) {
            cancelRequested = true
            currentPid?.let { YoutubeDL.getInstance().destroyProcessById(it) }
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested = true
            currentPid?.let { YoutubeDL.getInstance().destroyProcessById(it) }
            if (!DownloadState.running) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || DownloadState.running) return START_NOT_STICKY

        val format = Format.valueOf(intent.getStringExtra(EXTRA_FORMAT)!!)
        val quality = intent.getIntExtra(EXTRA_QUALITY, 0)
        val tree = Uri.parse(intent.getStringExtra(EXTRA_TREE)!!)

        // Tomamos la lista de seleccionados en el hilo principal (aquí lo estamos).
        val jobs = DownloadState.items.filter { it.checked }
        if (jobs.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        cancelRequested = false
        DownloadState.running = true
        DownloadState.post {
            totalCount = jobs.size; doneCount = 0; errorCount = 0
            finishedMessage = null; current = null; progress = 0f; phase = "Preparando…"
        }
        jobs.forEach { it.status = Status.PENDING; it.progress = 0f; it.error = null }

        ServiceCompat.startForeground(this, NOTIF_PROGRESS,
            progressNotification("Preparando…", 0, 0, jobs.size),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytmp3:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        scope.launch { runQueue(jobs, format, quality, tree) }
        return START_NOT_STICKY
    }

    private suspend fun runQueue(jobs: List<VideoItem>, format: Format, quality: Int, treeUri: Uri) {
        var ok = 0
        var failed = 0
        var fatal: String? = null
        try {
            App.ready.await()
            DownloadState.post { phase = "Comprobando actualizaciones de yt-dlp…" }
            Updater.ensureFresh(this, App.UPDATE_EVERY_MS)
            val tree = DocumentFile.fromTreeUri(this, treeUri)
            if (tree == null || !tree.canWrite()) {
                throw IOException("No hay permiso para escribir en la carpeta elegida. Vuelve a elegirla.")
            }
            val existing = tree.listFiles().mapNotNull { it.name }.toMutableSet()

            for ((idx, item) in jobs.withIndex()) {
                if (cancelRequested) break
                DownloadState.post {
                    current = item; progress = 0f; etaSec = -1; speed = ""
                    phase = "Obteniendo información…"
                }
                item.status = Status.DOWNLOADING
                notifyProgress(item.title, 0, idx, jobs.size, force = true)

                val success = downloadOne(item, idx, jobs.size, format, quality, tree, existing)
                if (cancelRequested && !success) { item.status = Status.PENDING; break }
                if (success) {
                    ok++
                    item.status = Status.DONE; item.progress = 100f; item.checked = false
                } else {
                    failed++
                    item.status = Status.ERROR
                }
                DownloadState.post { doneCount = ok; errorCount = failed }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "queue failed", e)
            fatal = e.message ?: e.toString()
        } finally {
            withContext(Dispatchers.Main) { finish(ok, failed, jobs.size, fatal) }
        }
    }

    private fun downloadOne(
        item: VideoItem, idx: Int, total: Int, format: Format, quality: Int,
        tree: DocumentFile, existing: MutableSet<String>,
    ): Boolean {
        val tmp = File(cacheDir, "dl/${item.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}")
        tmp.deleteRecursively(); tmp.mkdirs()

        // Intentos: cliente por defecto y, si YouTube responde 403 u otro bloqueo, clientes
        // alternativos. Si todos fallan, se fuerza una actualización de yt-dlp y se reintenta.
        val attempts = mutableListOf<String?>(null, "youtube:player_client=visionos,tv_simply",
            "youtube:player_client=tv,web_safari,mweb")
        var error: String? = null
        var forcedUpdate = false
        var i = 0
        while (i < attempts.size) {
            if (cancelRequested) break
            if (i > 0) {
                tmp.deleteRecursively(); tmp.mkdirs()
                DownloadState.post { phase = "Reintentando (${i + 1})…"; progress = 0f }
            }
            error = runYtdlp(item, idx, total, format, quality, tmp, attempts[i])
            if (error == null || hasOutput(tmp, format)) { error = null; break }
            if (!isRetryable(error)) break
            i++
            if (i == attempts.size && !forcedUpdate) {
                forcedUpdate = true
                DownloadState.post { phase = "Actualizando yt-dlp…" }
                if (runBlockingUpdate()) attempts += null   // un intento más con la versión nueva
            }
        }

        try {
            if (cancelRequested) return false
            // Aunque falle un paso secundario (p. ej. la portada), si el archivo existe lo guardamos.
            val out = findOutput(tmp, format)
            if (out == null) {
                item.error = shortError(error) ?: "No se generó el archivo"
                return false
            }
            DownloadState.post { phase = "Guardando en la carpeta…"; progress = 100f }
            item.savedAs = saveToTree(tree, out, format.mime, existing)
            return true
        } catch (e: Throwable) {
            item.error = "Error al guardar: ${e.message}"
            return false
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun findOutput(tmp: File, format: Format): File? = tmp.listFiles()
        ?.filter { it.isFile && it.extension.equals(format.ext, true) && it.length() > 0 }
        ?.maxByOrNull { it.length() }

    private fun hasOutput(tmp: File, format: Format) = findOutput(tmp, format) != null

    private fun isRetryable(err: String): Boolean {
        val e = err.lowercase()
        val permanent = listOf("private video", "video unavailable", "has been removed",
            "account associated", "not available in your country", "members-only", "join this channel")
        return permanent.none { it in e }
    }

    private fun runBlockingUpdate(): Boolean = try {
        kotlinx.coroutines.runBlocking { Updater.ensureFresh(this@DownloadService, 0) != null }
    } catch (e: Throwable) { false }

    /** Ejecuta yt-dlp una vez. Devuelve null si terminó bien o el mensaje de error. */
    private fun runYtdlp(
        item: VideoItem, idx: Int, total: Int, format: Format, quality: Int,
        tmp: File, extractorArgs: String?,
    ): String? {
        val req = YoutubeDLRequest(item.url).apply {
            if (extractorArgs != null) addOption("--extractor-args", extractorArgs)
            addOption("--no-playlist")
            addOption("--newline")
            addOption("--no-mtime")
            addOption("--windows-filenames")          // nombres válidos en tarjetas SD (FAT)
            addOption("--trim-filenames", 150)
            addOption("-o", "${tmp.absolutePath}/%(title)s.%(ext)s")
            addOption("--embed-metadata")
            when (format) {
                Format.MP3 -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", Quality.MP3_ARGS[quality])
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                }
                Format.MP4 -> {
                    val h = Quality.MP4_HEIGHT[quality]
                    addOption("-S", if (h > 0) "res:$h,vcodec:h264,ext:mp4:m4a" else "vcodec:h264,ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                }
            }
        }

        val pid = "dl-${SystemClock.elapsedRealtimeNanos()}"
        currentPid = pid
        return try {
            YoutubeDL.getInstance().execute(req, pid) { p, eta, line ->
                onLine(item, idx, total, p, eta, line)
            }
            null
        } catch (e: YoutubeDL.CanceledException) {
            "Cancelado"
        } catch (e: Throwable) {
            val err = e.message ?: e.toString()
            Log.w(TAG, "yt-dlp error (${extractorArgs ?: "default"}) for ${item.url}: $err")
            err
        } finally {
            currentPid = null
        }
    }

    private val speedRe = Regex("""at\s+(\S+/s)""")

    private fun onLine(item: VideoItem, idx: Int, total: Int, p: Float, eta: Long, line: String) {
        val newPhase = when {
            line.startsWith("[download]") && line.contains("%") -> "Descargando"
            line.startsWith("[ExtractAudio]") -> "Convirtiendo a MP3…"
            line.startsWith("[Merger]") -> "Uniendo audio y video…"
            line.startsWith("[EmbedThumbnail]") || line.startsWith("[ThumbnailsConvertor]") -> "Añadiendo portada…"
            line.startsWith("[Metadata]") -> "Escribiendo etiquetas…"
            else -> null
        }
        val sp = speedRe.find(line)?.groupValues?.get(1)
        val prog = p.coerceIn(0f, 100f)
        item.progress = prog
        DownloadState.post {
            if (newPhase != null) phase = newPhase
            progress = prog
            if (eta >= 0) etaSec = eta
            if (sp != null) speed = sp
        }
        notifyProgress(item.title, prog.toInt(), idx, total)
    }

    /** Copia el archivo a la carpeta elegida (Storage Access Framework). Evita sobrescribir. */
    private fun saveToTree(tree: DocumentFile, src: File, mime: String, existing: MutableSet<String>): String {
        val base = src.nameWithoutExtension
        val ext = src.extension
        var name = src.name
        // Si ya existe el mismo archivo (mismo nombre y tamaño), no lo duplicamos.
        if (name in existing) {
            val same = tree.findFile(name)
            if (same != null && same.length() == src.length()) return "$name (ya estaba)"
        }
        var n = 2
        while (name in existing) name = "$base ($n).$ext".also { n++ }
        val doc = tree.createFile(mime, name) ?: throw IOException("No se pudo crear $name")
        contentResolver.openOutputStream(doc.uri, "w")!!.use { out ->
            src.inputStream().use { it.copyTo(out, 256 * 1024) }
        }
        val finalName = doc.name ?: name
        existing += finalName
        return finalName
    }

    private fun shortError(err: String?): String? {
        if (err.isNullOrBlank()) return null
        val line = err.lines().lastOrNull { it.contains("ERROR") } ?: err.lines().last { it.isNotBlank() }
        return line.removePrefix("ERROR:").trim().take(300)
    }

    // ------------------------------------------------------------------ notificaciones
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun progressNotification(title: String, pct: Int, idx: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, App.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Descargando ${idx + 1} de $total")
            .setContentText(title)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    private fun notifyProgress(title: String, pct: Int, idx: Int, total: Int, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotif < 1000) return
        lastNotif = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_PROGRESS, progressNotification(title, pct, idx, total))
    }

    private fun finish(ok: Int, failed: Int, total: Int, fatal: String?) {
        val msg = when {
            fatal != null -> "Error: $fatal"
            cancelRequested -> "Cancelado. $ok de $total descargados."
            failed == 0 -> "¡Listo! $ok de $total descargados."
            else -> "Terminado: $ok descargados, $failed con error (puedes reintentarlos)."
        }
        DownloadState.running = false
        DownloadState.post {
            current = null; phase = ""; finishedMessage = msg
            doneCount = ok; errorCount = failed
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE,
            NotificationCompat.Builder(this, App.CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("YT MP3")
                .setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build())
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
