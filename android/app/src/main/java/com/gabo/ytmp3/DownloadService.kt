package com.gabo.ytmp3

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Servicio en primer plano que descarga la cola. Ejecuta varias descargas a la vez
 * (según el modo Normal / Rápido / Ultra) y un "regulador" ajusta el número cada pocos
 * segundos según temperatura, batería, tipo de red y bloqueos de YouTube.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "YTMP3"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2
        private const val NOTIF_ALERT = 3
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_TREE = "tree"
        const val EXTRA_OPEN_VERIFY = "open_verify"

        @Volatile private var cancelRequested = false
        @Volatile private var verifyPaused = false
        private val pids = ConcurrentHashMap.newKeySet<String>()

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
            killAll()
            if (DownloadState.running) {
                ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
            }
        }

        /** El usuario resolvió la verificación de YouTube: reanudar la cola. */
        fun verificationDone() {
            verifyPaused = false
            DownloadState.post { needsVerification = false; phase = "" }
        }

        private fun killAll() {
            pids.toList().forEach { YoutubeDL.getInstance().destroyProcessById(it) }
            killOrphans()
        }

        /**
         * La librería intenta matar los procesos hijos con `pstree`, que no existe en Android,
         * así que QuickJS/ffmpeg quedarían huérfanos. Los buscamos en /proc (solo los de esta app).
         */
        private fun killOrphans() {
            val me = android.os.Process.myPid()
            val uid = android.os.Process.myUid()
            File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                try {
                    if (android.system.Os.stat(d.path).st_uid != uid) return@forEach
                    val cmd = File(d, "cmdline").readText()
                    if (listOf("libqjs.so", "libffmpeg.so", "libpython.so").any { it in cmd }) {
                        android.os.Process.killProcess(pid)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var prefs: Prefs

    // regulador
    @Volatile private var limit = 1
    @Volatile private var botBackoffUntil = 0L
    @Volatile private var heatTripped = false
    private var verifyCount = 0

    // estadísticas de la tanda
    private val itemSeconds = java.util.concurrent.atomic.AtomicLong(0)
    private val bytesSaved = java.util.concurrent.atomic.AtomicLong(0)
    private val inFlight = ConcurrentHashMap.newKeySet<VideoItem>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested = true
            verifyPaused = false
            killAll()
            if (!DownloadState.running) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || DownloadState.running) return START_NOT_STICKY

        prefs = Prefs(this)
        val format = Format.valueOf(intent.getStringExtra(EXTRA_FORMAT)!!)
        val quality = intent.getIntExtra(EXTRA_QUALITY, 0)
        val tree = Uri.parse(intent.getStringExtra(EXTRA_TREE)!!)

        val jobs = DownloadState.items.filter { it.checked }
        if (jobs.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        cancelRequested = false
        verifyPaused = false
        heatTripped = false
        verifyCount = 0
        DownloadState.running = true
        val mode = prefs.speedMode
        limit = Device.limitFor(this, mode)
        jobs.forEach { it.status = Status.PENDING; it.progress = 0f; it.error = null; it.phase = ""; it.speedBps = 0.0 }
        DownloadState.post {
            totalCount = jobs.size; doneCount = 0; errorCount = 0
            finishedMessage = null; active.clear(); phase = "Preparando…"
            speedHistory.clear(); speedBps = 0.0; totalEtaSec = -1; perMinute = 0.0
            this.mode = mode; concurrency = limit; notice = null; needsVerification = false
        }

        ServiceCompat.startForeground(this, NOTIF_PROGRESS,
            progressNotification("Preparando…", 0, 0, jobs.size),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        // Mantener CPU y Wi-Fi despiertos con la pantalla bloqueada.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytmp3:download")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(
                if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "ytmp3:wifi")
            .apply { setReferenceCounted(false); acquire() }

        scope.launch { runQueue(jobs, format, quality, tree, mode) }
        return START_NOT_STICKY
    }

    private suspend fun runQueue(jobs: List<VideoItem>, format: Format, quality: Int, treeUri: Uri, mode: SpeedMode) {
        val ok = AtomicInteger(0)
        val failed = AtomicInteger(0)
        var fatal: String? = null
        val started = SystemClock.elapsedRealtime()
        try {
            App.ready.await()
            DownloadState.post { phase = "Comprobando actualizaciones de yt-dlp…" }
            Updater.ensureFresh(this, App.UPDATE_EVERY_MS)
            val tree = DocumentFile.fromTreeUri(this, treeUri)
            if (tree == null || !tree.canWrite()) {
                throw IOException("No hay permiso para escribir en la carpeta elegida. Vuelve a elegirla.")
            }
            val existing = tree.listFiles().mapNotNull { it.name }.toMutableSet()
            DownloadState.post { phase = "" }

            val queue = ArrayDeque(jobs)
            val maxWorkers = Device.maxParallel(this)

            coroutineScope {
                val governor = launch { governorLoop(mode, jobs.size, ok, failed, started) }

                val workers = (0 until maxWorkers).map { slot ->
                    launch {
                        while (isActive && !cancelRequested) {
                            if (verifyPaused || slot >= limit) {
                                val empty = synchronized(queue) { queue.isEmpty() }
                                if (empty) break
                                delay(400); continue
                            }
                            val item = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                            val t0 = SystemClock.elapsedRealtime()
                            item.status = Status.DOWNLOADING
                            item.phase = "Obteniendo información…"
                            inFlight += item
                            DownloadState.post { active += item }

                            val r = downloadOne(item, format, quality, tree, existing)
                            when (r) {
                                Result.OK -> {
                                    ok.incrementAndGet()
                                    item.status = Status.DONE; item.progress = 100f; item.checked = false
                                }
                                Result.FAILED -> { failed.incrementAndGet(); item.status = Status.ERROR }
                                Result.BOT -> {
                                    // Volver a la cola y pausar hasta que el usuario verifique.
                                    if (requestVerification(item)) {
                                        item.status = Status.PENDING; item.phase = "En espera de verificación"
                                        synchronized(queue) { queue.addFirst(item) }
                                    } else {
                                        failed.incrementAndGet(); item.status = Status.ERROR
                                    }
                                }
                                Result.CANCELLED -> item.status = Status.PENDING
                            }
                            item.speedBps = 0.0
                            inFlight -= item
                            if (r == Result.OK || r == Result.FAILED) {
                                itemSeconds.addAndGet((SystemClock.elapsedRealtime() - t0) / 1000)
                            }
                            DownloadState.post {
                                active -= item
                                doneCount = ok.get(); errorCount = failed.get()
                            }
                        }
                    }
                }
                workers.forEach { it.join() }
                governor.cancel()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "queue failed", e)
            fatal = e.message ?: e.toString()
        } finally {
            val wall = (SystemClock.elapsedRealtime() - started) / 1000
            withContext(Dispatchers.Main) { finish(ok.get(), failed.get(), jobs.size, fatal, wall) }
        }
    }

    // ------------------------------------------------------------------ regulador
    private suspend fun governorLoop(mode: SpeedMode, total: Int, ok: AtomicInteger, failed: AtomicInteger, started: Long) {
        var tick = 0
        while (true) {
            if (tick % 4 == 0) recomputeLimit(mode)   // cada ~2 s
            tick++

            // Muestra de velocidad total + estimación de tiempo restante.
            val act = inFlight.toList()
            val speed = act.sumOf { it.speedBps }
            val finished = ok.get() + failed.get()
            val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
            val eta = if (finished >= 1 && elapsed > 5) {
                val perItem = elapsed / (finished + act.sumOf { it.progress.toDouble() } / 100.0)
                ((total - finished - act.sumOf { it.progress.toDouble() } / 100.0) * perItem).toLong()
            } else -1L
            val rate = if (elapsed > 20 && finished > 0) finished / (elapsed / 60.0) else 0.0
            DownloadState.post {
                perMinute = rate
                speedBps = speed
                speedHistory.addLast((speed / 1024 / 1024).toFloat())
                while (speedHistory.size > 80) speedHistory.removeFirst()
                totalEtaSec = eta
                concurrency = limit
            }
            val first = act.firstOrNull()
            notifyProgress(first?.title ?: "Preparando…", (first?.progress ?: 0f).toInt(), finished, total, act.size)
            delay(500)
        }
    }

    private fun recomputeLimit(mode: SpeedMode) {
        var l = Device.limitFor(this, mode)
        var note: String? = null

        if (mode == SpeedMode.ULTRA && prefs.ultraWifiOnly && !Device.isUnmetered(this)) {
            l = Device.limitFor(this, SpeedMode.FAST)
            note = "Ultra en espera: estás con datos móviles (usando modo Rápido)"
        }

        val bat = Device.battery(this)
        if (prefs.heatProtect) {
            val t = bat.tempC ?: 0f
            val th = Device.thermal(this)
            val hot = th >= 3 || t >= 45f            // severo
            val warm = th >= 2 || t >= 42f           // moderado
            if (warm) heatTripped = true
            if (heatTripped && t < 38f && th <= 1) heatTripped = false   // se enfrió de verdad
            when {
                hot -> { l = 1; note = "🌡️ Celular muy caliente (${t.toInt()} °C): 1 descarga a la vez" }
                heatTripped -> {
                    l = min(l, 2)
                    note = if (mode == SpeedMode.ULTRA) "🌡️ Modo Ultra desactivado por calor (${t.toInt()} °C)"
                           else "🌡️ Celular caliente (${t.toInt()} °C): bajando el ritmo"
                }
            }
        }
        if (bat.pct in 0..14 && !bat.charging) {
            l = min(l, 2)
            note = note ?: "🔋 Batería baja (${bat.pct} %): bajando el ritmo"
        }
        if (SystemClock.elapsedRealtime() < botBackoffUntil) {
            l = 1
            note = note ?: "YouTube nos pidió ir más despacio: 1 descarga a la vez por unos minutos"
        }
        val changed = l != limit
        limit = l.coerceAtLeast(1)
        if (changed || note != DownloadState.notice) {
            val n = note
            DownloadState.post { concurrency = limit; notice = n }
            if (changed && n != null && n.startsWith("🌡️")) alert("Protección de calor", n, openVerify = false)
        }
    }

    // ------------------------------------------------------------------ verificación "no soy un robot"
    /** Pausa la cola para que el usuario verifique. false = ya se intentó demasiadas veces. */
    private fun requestVerification(item: VideoItem): Boolean {
        synchronized(this) {
            if (verifyPaused) return true
            botBackoffUntil = SystemClock.elapsedRealtime() + 5 * 60 * 1000
            if (verifyCount >= 3) {
                // Ya verificó varias veces y sigue bloqueado: no insistir, marcar error.
                item.error = "YouTube sigue bloqueando. Prueba más tarde o con otra red."
                return false
            }
            verifyCount++
            verifyPaused = true
        }
        killAll()   // las demás descargas también fallarían; se reintentan tras verificar
        DownloadState.post { needsVerification = true; phase = "En pausa: YouTube pide verificar que no eres un robot" }
        alert("YouTube pide verificación",
            "Toca para resolver la verificación y continuar con las descargas.", openVerify = true)
        return true
    }

    private enum class Result { OK, FAILED, BOT, CANCELLED }

    private fun downloadOne(
        item: VideoItem, format: Format, quality: Int,
        tree: DocumentFile, existing: MutableSet<String>,
    ): Result {
        val tmp = File(cacheDir, "dl/${item.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}")
        tmp.deleteRecursively(); tmp.mkdirs()

        // Intentos: cliente por defecto y, si YouTube responde 403 u otro error, clientes
        // alternativos. Si todos fallan, se fuerza una actualización de yt-dlp y se reintenta.
        val attempts = mutableListOf<String?>(null, "youtube:player_client=visionos,tv_simply",
            "youtube:player_client=tv,web_safari,mweb")
        var error: String? = null
        var forcedUpdate = false
        var i = 0
        try {
            while (i < attempts.size) {
                if (cancelRequested) return Result.CANCELLED
                if (verifyPaused) return Result.BOT.also { item.phase = "" }
                if (i > 0) {
                    tmp.deleteRecursively(); tmp.mkdirs()
                    item.phase = "Reintentando (${i + 1})…"; item.progress = 0f
                }
                error = runYtdlp(item, format, quality, tmp, attempts[i])
                if (error == null || hasOutput(tmp, format)) { error = null; break }
                if (cancelRequested) return Result.CANCELLED
                if (Util.isBotCheck(error)) return Result.BOT
                if (!isRetryable(error)) break
                i++
                if (i == attempts.size && !forcedUpdate) {
                    forcedUpdate = true
                    item.phase = "Actualizando yt-dlp…"
                    if (runBlockingUpdate()) attempts += null
                }
            }

            val out = findOutput(tmp, format)
            if (out == null) {
                item.error = item.error ?: shortError(error) ?: "No se generó el archivo"
                return Result.FAILED
            }
            item.phase = "Guardando…"; item.progress = 100f
            item.savedAs = saveToTree(tree, out, format.mime, existing)
            bytesSaved.addAndGet(out.length())
            return Result.OK
        } catch (e: Throwable) {
            item.error = "Error al guardar: ${e.message}"
            return Result.FAILED
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
        runBlocking { Updater.ensureFresh(this@DownloadService, 0) != null }
    } catch (e: Throwable) { false }

    /** Ejecuta yt-dlp una vez. Devuelve null si terminó bien o el mensaje de error. */
    private fun runYtdlp(item: VideoItem, format: Format, quality: Int, tmp: File, extractorArgs: String?): String? {
        val cookies = Cookies.tempCopy(this)
        val req = YoutubeDLRequest(item.url).apply {
            if (extractorArgs != null) addOption("--extractor-args", extractorArgs)
            if (cookies != null) addOption("--cookies", cookies.absolutePath)
            // Caché compartida: el desafío JS de YouTube se resuelve una vez y lo reutilizan todas.
            addOption("--cache-dir", App.cacheDir(this@DownloadService).absolutePath)
            addOption("--no-playlist")
            addOption("--newline")
            addOption("--no-mtime")
            addOption("--windows-filenames")          // nombres válidos en tarjetas SD (FAT)
            addOption("--trim-filenames", 150)
            addOption("--concurrent-fragments", 4)    // trozos en paralelo cuando el formato lo permite
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

        val pid = "dl-${SystemClock.elapsedRealtimeNanos()}-${item.id}"
        pids += pid
        return try {
            YoutubeDL.getInstance().execute(req, pid) { p, eta, line -> onLine(item, p, eta, line) }
            null
        } catch (e: YoutubeDL.CanceledException) {
            "Cancelado"
        } catch (e: Throwable) {
            val err = e.message ?: e.toString()
            Log.w(TAG, "yt-dlp error (${extractorArgs ?: "default"}) for ${item.url}: ${err.takeLast(500)}")
            err
        } finally {
            pids -= pid
            cookies?.delete()
        }
    }

    private fun onLine(item: VideoItem, p: Float, eta: Long, line: String) {
        val newPhase = when {
            line.startsWith("[download]") && line.contains("%") -> "Descargando"
            line.startsWith("[ExtractAudio]") -> "Convirtiendo a MP3…"
            line.startsWith("[Merger]") -> "Uniendo audio y video…"
            line.startsWith("[EmbedThumbnail]") || line.startsWith("[ThumbnailsConvertor]") -> "Añadiendo portada…"
            line.startsWith("[Metadata]") -> "Escribiendo etiquetas…"
            else -> null
        }
        if (newPhase != null) item.phase = newPhase
        if (newPhase != null && newPhase != "Descargando") item.speedBps = 0.0
        Util.parseSpeed(line)?.let { item.speedBps = it }
        item.progress = p.coerceIn(0f, 100f)
        if (eta >= 0) item.etaSec = eta
    }

    /** Copia el archivo a la carpeta elegida (Storage Access Framework). Evita duplicados. */
    private fun saveToTree(tree: DocumentFile, src: File, mime: String, existing: MutableSet<String>): String {
        val base = src.nameWithoutExtension
        val ext = src.extension
        val name: String
        synchronized(existing) {
            if (src.name in existing) {
                val same = tree.findFile(src.name)
                if (same != null && same.length() == src.length()) return "${src.name} (ya estaba)"
            }
            var n2 = src.name
            var n = 2
            while (n2 in existing) n2 = "$base ($n).$ext".also { n++ }
            name = n2
            existing += name    // reservar el nombre antes de copiar (otras descargas en paralelo)
        }
        val doc = tree.createFile(mime, name) ?: throw IOException("No se pudo crear $name")
        contentResolver.openOutputStream(doc.uri, "w")!!.use { out ->
            src.inputStream().use { it.copyTo(out, 256 * 1024) }
        }
        val finalName = doc.name ?: name
        synchronized(existing) { existing += finalName }
        return finalName
    }

    private fun shortError(err: String?): String? {
        if (err.isNullOrBlank()) return null
        val line = err.lines().lastOrNull { it.contains("ERROR") } ?: err.lines().last { it.isNotBlank() }
        return line.removePrefix("ERROR:").trim().take(300)
    }

    // ------------------------------------------------------------------ notificaciones
    private fun openAppIntent(verify: Boolean = false): PendingIntent = PendingIntent.getActivity(
        this, if (verify) 7 else 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_VERIFY, verify),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun progressNotification(title: String, pct: Int, done: Int, total: Int, parallel: Int = 0): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE)
        val head = if (parallel > 1) "Descargando $parallel a la vez · $done de $total" else "Descargando · $done de $total"
        return NotificationCompat.Builder(this, App.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(head)
            .setContentText(title)
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    private var lastNotif = 0L
    private fun notifyProgress(title: String, pct: Int, done: Int, total: Int, parallel: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotif < 1500) return
        lastNotif = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_PROGRESS, progressNotification(title, pct, done, total, parallel))
    }

    private fun alert(title: String, text: String, openVerify: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT,
            NotificationCompat.Builder(this, App.CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openAppIntent(openVerify))
                .setAutoCancel(true)
                .build())
    }

    private fun finish(ok: Int, failed: Int, total: Int, fatal: String?, wallSec: Long) {
        val msg = when {
            fatal != null -> "Error: $fatal"
            cancelRequested -> "Cancelado. $ok de $total descargados."
            failed == 0 -> "¡Listo! $ok de $total descargados."
            else -> "Terminado: $ok descargados, $failed con error (puedes reintentarlos)."
        }
        // Estadísticas: tiempo ahorrado = suma de tiempos individuales − tiempo real.
        if (ok > 0) {
            prefs.statSongs += ok
            prefs.statBytes += bytesSaved.get()
            prefs.statSavedSec += (itemSeconds.get() - wallSec).coerceAtLeast(0)
            prefs.addDaily(ok)
        }
        DownloadState.running = false
        val party = fatal == null && !cancelRequested && failed == 0 && ok > 0
        DownloadState.post {
            active.clear(); phase = ""; finishedMessage = msg
            doneCount = ok; errorCount = failed; speedBps = 0.0
            needsVerification = false; notice = null
            celebrate = party
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT)
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
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
