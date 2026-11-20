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
        private const val EXTRA_PLAYLISTS = "playlists"
        private const val EXTRA_LOCAL = "local_path"
        const val EXTRA_OPEN_VERIFY = "open_verify"

        @Volatile private var cancelRequested = false
        @Volatile private var verifyPaused = false
        private val pids = ConcurrentHashMap.newKeySet<String>()

        fun start(ctx: Context, format: Format, quality: Int, playlists: LongArray, localPath: String? = null) {
            val i = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FORMAT, format.name)
                .putExtra(EXTRA_QUALITY, quality)
                .putExtra(EXTRA_PLAYLISTS, playlists)
                .putExtra(EXTRA_LOCAL, localPath)
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
        val targets = intent.getLongArrayExtra(EXTRA_PLAYLISTS)?.toList().orEmpty()
        localPath = intent.getStringExtra(EXTRA_LOCAL)

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

        scope.launch { runQueue(jobs, format, quality, targets, mode) }
        return START_NOT_STICKY
    }

    private suspend fun runQueue(jobs: List<VideoItem>, format: Format, quality: Int, targets: List<Long>, mode: SpeedMode) {
        val ok = AtomicInteger(0)
        val failed = AtomicInteger(0)
        var fatal: String? = null
        val started = SystemClock.elapsedRealtime()
        try {
            App.ready.await()
            DownloadState.post { phase = "Comprobando actualizaciones de yt-dlp…" }
            Updater.ensureFresh(this, App.UPDATE_EVERY_MS)
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

                            val r = downloadOne(item, format, quality, targets)
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
            Analyzer.kick(this)
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
        // Las canciones de Spotify se bajan de YouTube: la verificación es la de YouTube.
        val p = if (item.platform == Platform.SPOTIFY) Platform.YOUTUBE else item.platform
        synchronized(this) {
            if (verifyPaused) return true
            if (p == Platform.YOUTUBE) botBackoffUntil = SystemClock.elapsedRealtime() + 5 * 60 * 1000
            if (verifyCount >= 3) {
                // Ya verificó varias veces y sigue bloqueado: no insistir, marcar error.
                item.error = if (p == Platform.INSTAGRAM) "Instagram no permite descargar este contenido con tu sesión."
                             else "${p.label} sigue bloqueando. Prueba más tarde o con otra red."
                return false
            }
            verifyCount++
            verifyPaused = true
        }
        killAll()   // las demás descargas también fallarían; se reintentan tras verificar
        val (phaseMsg, title, text) = when (p) {
            Platform.INSTAGRAM -> Triple("En pausa: Instagram pide iniciar sesión",
                "Instagram pide iniciar sesión", "Toca para iniciar sesión y continuar con las descargas.")
            Platform.SPOTIFY -> Triple("En pausa: YouTube pide verificar que no eres un robot",
                "YouTube pide verificación", "Toca para resolver la verificación y continuar con las descargas.")
            Platform.FACEBOOK -> Triple("En pausa: Facebook pide iniciar sesión",
                "Facebook pide iniciar sesión", "Toca para iniciar sesión y continuar con las descargas.")
            Platform.TIKTOK -> Triple("En pausa: TikTok pide verificación",
                "TikTok pide verificación", "Toca para resolverlo y continuar con las descargas.")
            Platform.YOUTUBE -> Triple("En pausa: YouTube pide verificar que no eres un robot",
                "YouTube pide verificación", "Toca para resolver la verificación y continuar con las descargas.")
        }
        DownloadState.post { needsVerification = true; verifyPlatform = p; phase = phaseMsg }
        alert(title, text, openVerify = true)
        return true
    }

    private enum class Result { OK, FAILED, BOT, CANCELLED }

    /** Carpeta del celular donde guardar también (null = solo en la biblioteca de la app). */
    @Volatile private var localPath: String? = null

    private fun fileName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(90).ifEmpty { "Cancion" }

    /** Copia el MP3 a la carpeta elegida (visible en otras apps). Devuelve su content:// o ruta. */
    private fun exportAudio(src: File, title: String, artist: String, rel: String): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val col = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, fileName(title) + ".mp3")
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, rel)
                put(android.provider.MediaStore.Audio.Media.TITLE, title)
                if (artist.isNotBlank()) put(android.provider.MediaStore.Audio.Media.ARTIST, artist)
                put(android.provider.MediaStore.Audio.Media.IS_MUSIC, 1)
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = runCatching { contentResolver.insert(col, cv) }.getOrNull() ?: run {
                // carpetas fuera de "Música": se usa Música/<nombre>
                cv.put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/" + rel.trimEnd('/').substringAfterLast('/') + "/")
                contentResolver.insert(col, cv)
            } ?: return@runCatching null
            contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
            cv.clear(); cv.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)
            uri.toString()
        } else {
            val dir = File(android.os.Environment.getExternalStorageDirectory(), rel).apply { mkdirs() }
            val dst = File(dir, fileName(title) + ".mp3")
            src.copyTo(dst, overwrite = true)
            android.media.MediaScannerConnection.scanFile(this, arrayOf(dst.absolutePath), null, null)
            dst.absolutePath
        }
    }.onFailure { android.util.Log.w("DSDownload", "exportAudio", it) }.getOrNull()

    /** El MP4 va a Películas/<carpeta> (Android no deja videos en Música). */
    private fun exportVideo(src: File, title: String, rel: String) {
        runCatching {
            val folder = rel.trimEnd('/').substringAfterLast('/')
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val col = android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName(title) + ".mp4")
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/$folder/")
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(col, cv) ?: return@runCatching
                contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
                cv.clear(); cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            } else {
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), folder).apply { mkdirs() }
                val dst = File(dir, fileName(title) + ".mp4"); src.copyTo(dst, overwrite = true)
                android.media.MediaScannerConnection.scanFile(this, arrayOf(dst.absolutePath), null, null)
            }
        }.onFailure { android.util.Log.w("DSDownload", "exportVideo", it) }
    }

    private fun downloadOne(item: VideoItem, format: Format, quality: Int, targets: List<Long>): Result {
        // ¿Ya está en la biblioteca? Solo se agrega a las playlists (sin volver a bajarlo).
        val key = "${item.platform.name.lowercase()}:${item.id}"
        val wantVideo = format == Format.MP4 && item.platform != Platform.SPOTIFY
        val known = runBlocking { Library.dao(this@DownloadService).byKey(key) }
        if (known != null && (!wantVideo || known.hasVideo)) {
            val rel = localPath
            if (rel != null && known.localUri == null && known.audioPath != null && File(known.audioPath).exists()) {
                exportAudio(File(known.audioPath), known.title, known.artist, rel)?.let { where ->
                    val upd = if (where.startsWith("content://")) known.copy(localUri = where) else known.copy(audioPath = where)
                    runBlocking { Library.dao(this@DownloadService).updateTrack(upd) }
                    if (where.startsWith("content://") || where != known.audioPath) runCatching { File(known.audioPath).delete() }
                }
            }
            runBlocking { Library.addToPlaylists(this@DownloadService, listOf(known.id), targets) }
            item.savedAs = "${known.title} (ya estaba)"
            return Result.OK
        }
        val tmp = File(cacheDir, "dl/${item.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}")
        tmp.deleteRecursively(); tmp.mkdirs()

        // Intentos: cliente por defecto y, si YouTube responde 403 u otro error, clientes
        // alternativos. Si todos fallan, se fuerza una actualización de yt-dlp y se reintenta.
        val attempts = if (item.platform == Platform.SPOTIFY)
            mutableListOf<String?>("sp:filter", "sp:plain")   // 1º con filtro de duración, 2º sin filtro
        else if (item.platform == Platform.YOUTUBE)
            mutableListOf<String?>(null, "youtube:player_client=visionos,tv_simply", "youtube:player_client=tv,web_safari,mweb")
        else mutableListOf<String?>(null, null)   // TikTok/Instagram: un reintento simple
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
                if (item.platform != Platform.YOUTUBE && item.platform != Platform.SPOTIFY && Util.needsLogin(error)) return Result.BOT
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
            item.savedAs = saveToLibrary(item, key, tmp, targets)
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
        val spotify = item.platform == Platform.SPOTIFY
        val format = if (spotify) Format.MP3 else format    // de Spotify solo tiene sentido el audio
        val reqUrl = if (spotify) {
            val q = item.url.removePrefix("spotify:")
            if (extractorArgs == "sp:plain") "ytsearch1:$q audio" else "ytsearch5:$q"
        } else item.url
        val req = YoutubeDLRequest(reqUrl).apply {
            if (extractorArgs != null && !spotify) addOption("--extractor-args", extractorArgs)
            if (spotify) {
                // Elegir el resultado cuya duración coincide con la de Spotify (evita en vivo/remix).
                val d = item.duration?.toInt()
                if (extractorArgs == "sp:filter" && d != null && d > 0) {
                    addOption("--match-filter", "duration>=${d - 25} & duration<=${d + 25}")
                }
                addOption("--max-downloads", 1)
            }
            if (cookies != null) addOption("--cookies", cookies.absolutePath)
            // Caché compartida: el desafío JS de YouTube se resuelve una vez y lo reutilizan todas.
            addOption("--cache-dir", App.cacheDir(this@DownloadService).absolutePath)
            if (!spotify) addOption("--no-playlist")
            addOption("--newline")
            addOption("--no-mtime")
            addOption("--windows-filenames")          // nombres válidos en tarjetas SD (FAT)
            addOption("--trim-filenames", 150)
            addOption("--concurrent-fragments", 4)    // trozos en paralelo cuando el formato lo permite
            // YouTube: título; TikTok/Instagram: autor + descripción corta (sus títulos son largos).
            if (item.platform != Platform.YOUTUBE) {
                // Quitar hashtags del título para que el nombre del archivo quede limpio.
                addCommands(listOf("--replace-in-metadata", "title", "\\s*#\\S+", ""))
            }
            if (item.platform == Platform.FACEBOOK) {
                // "9.9K views · 340 reactions | Texto" → "Texto"
                addCommands(listOf("--replace-in-metadata", "title", "^[^|]*\\|\\s*", ""))
            }
            addOption("-o", if (spotify) "${tmp.absolutePath}/${safeName(item.title)}.%(ext)s"
                            else if (item.platform == Platform.YOUTUBE) "${tmp.absolutePath}/%(title)s.%(ext)s"
                            else "${tmp.absolutePath}/%(uploader,channel|video)s - %(title).60B [%(id)s].%(ext)s")
            addOption("--embed-metadata")
            addOption("--write-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            when (format) {
                Format.MP3 -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", Quality.MP3_ARGS[quality])
                    addOption("--embed-thumbnail")
                }
                Format.MP4 -> {
                    val h = Quality.MP4_HEIGHT[quality]
                    val res = if (h > 0) "res:$h," else ""
                    if (item.platform == Platform.TIKTOK) {
                        // Siempre la versión sin marca de agua; H.264 para que se vea en cualquier teléfono.
                        addOption("-f", "b[format_note!*=atermark]/bv*[format_note!*=atermark]+ba/b")
                        addOption("-S", "${res}vcodec:h264,br")
                    } else if (item.platform == Platform.FACEBOOK) {
                        // Facebook: el archivo progresivo "hd"/"sd" ya trae audio; si no, se une video+audio.
                        addOption("-f", "hd/sd/bv*+ba/b")
                        addOption("-S", "${res}vcodec:h264,ext:mp4:m4a")
                    } else {
                        addOption("-f", "bv*+ba/b")
                        addOption("-S", "${res}vcodec:h264,ext:mp4:m4a")
                    }
                    addOption("--merge-output-format", "mp4")
                    // También el audio en MP3 (oculto): con "ver video" apagado suena este.
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "192K")
                    addOption("-k")
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

    /** Nombre de archivo seguro: "Artista - Canción". */
    private fun safeName(s: String) = s.replace(Regex("[\\/:*?\"<>|%]"), "_").trim().take(120).ifEmpty { "cancion" }

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

    /** Mueve lo descargado a la biblioteca privada y lo agrega a las playlists elegidas. */
    private fun saveToLibrary(item: VideoItem, key: String, tmp: File, targets: List<Long>): String {
        val files = tmp.listFiles().orEmpty().filter { it.isFile && it.length() > 0 }
        val mp3 = files.filter { it.extension.equals("mp3", true) }.maxByOrNull { it.length() }
        val mp4 = files.filter { it.extension.equals("mp4", true) }.maxByOrNull { it.length() }
        val jpg = files.firstOrNull { it.extension.equals("jpg", true) || it.extension.equals("jpeg", true) }
        val safe = Library.safeKey(key)
        fun move(src: File?, sub: String, ext: String): File? {
            src ?: return null
            val dst = File(Library.dir(this, sub), "$safe.$ext")
            dst.delete()
            if (!src.renameTo(dst)) { src.copyTo(dst, overwrite = true); src.delete() }
            return dst
        }
        val audio = move(mp3, "audio", "mp3")
        val video = move(mp4, "video", "mp4")
        var cover = move(jpg, "covers", "jpg")
        if (item.platform == Platform.SPOTIFY && item.thumbUrl != null) {
            runCatching {
                val f = File(Library.dir(this, "covers"), "$safe.jpg")
                // la portada de esta canción (no la de la playlist)
                val url = SpotifySource.trackCover(item.id) ?: item.thumbUrl
                java.net.URL(url).openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
                cover = f
            }
        }
        val durMs = item.duration?.let { (it * 1000).toLong() } ?: runCatching {
            android.media.MediaMetadataRetriever().run {
                setDataSource((audio ?: video)!!.absolutePath)
                val d = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                release(); d
            }
        }.getOrDefault(0L)
        bytesSaved.addAndGet((audio?.length() ?: 0) + (video?.length() ?: 0))
        val title = item.title.ifBlank { (audio ?: video)?.nameWithoutExtension ?: "Canción" }
        // Guardar también en la carpeta del celular: el MP3 se mueve allí (sin ocupar el doble)
        var audioPath = audio?.absolutePath
        var localUri: String? = null
        val audioBytes = audio?.length() ?: 0
        localPath?.let { rel ->
            if (audio != null) exportAudio(audio, title, item.uploader.orEmpty(), rel)?.let { where ->
                if (where.startsWith("content://")) { localUri = where; audioPath = null } else audioPath = where
                audio.delete()
            }
            if (video != null) exportVideo(video, title, rel)
        }
        runBlocking {
            val id = Library.upsert(this@DownloadService, Track(key = key, title = title,
                artist = item.uploader.orEmpty(), durationMs = durMs,
                audioPath = audioPath, localUri = localUri, videoPath = video?.absolutePath, coverPath = cover?.absolutePath,
                audioBytes = audioBytes, videoBytes = video?.length() ?: 0))
            Library.addToPlaylists(this@DownloadService, listOf(id), targets)
        }
        return title
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
                .setContentTitle("DownSong")
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
