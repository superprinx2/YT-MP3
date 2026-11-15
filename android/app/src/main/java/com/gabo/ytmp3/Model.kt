package com.gabo.ytmp3

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Format(val ext: String, val mime: String) {
    MP3("mp3", "audio/mpeg"),
    MP4("mp4", "video/mp4")
}

enum class Status { PENDING, DOWNLOADING, DONE, ERROR }

/** Modos de velocidad. ULTRA calcula el máximo según el celular. */
enum class SpeedMode(val label: String) { NORMAL("Normal"), FAST("Rápido"), ULTRA("Ultra") }

class VideoItem(
    val id: String,
    val title: String,
    val url: String,
    val duration: Double?,
    var checked: Boolean = true,
) {
    @Volatile var status: Status = Status.PENDING
    @Volatile var progress: Float = 0f
    @Volatile var error: String? = null
    @Volatile var savedAs: String? = null
    @Volatile var phase: String = ""
    @Volatile var speedBps: Double = 0.0
    @Volatile var etaSec: Long = -1

    val thumbnail: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val thumbnailHq: String get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

/**
 * Estado compartido entre la Activity y el servicio de descarga.
 * Todas las escrituras de campos de UI se hacen en el hilo principal (vía [post]).
 */
object DownloadState {
    val items = mutableListOf<VideoItem>()
    var listTitle: String? = null
    var isPlaylist = false

    @Volatile var running = false
    val active = LinkedHashSet<VideoItem>()   // descargándose ahora mismo
    var phase = ""                            // mensaje global (p. ej. "Comprobando actualizaciones…")
    var doneCount = 0
    var errorCount = 0
    var totalCount = 0
    var finishedMessage: String? = null

    var speedBps = 0.0                        // velocidad total de todas las descargas
    val speedHistory = ArrayDeque<Float>()    // MB/s, últimas muestras para el gráfico
    var concurrency = 1                       // descargas simultáneas en este momento
    var mode = SpeedMode.NORMAL
    var notice: String? = null                // avisos: calor, batería, datos móviles…
    var needsVerification = false             // YouTube pidió "no soy un robot"
    var totalEtaSec = -1L
    var perMinute = 0.0                       // canciones terminadas por minuto
    var celebrate = false                     // dispara el confeti una vez

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Ejecuta [block] en el hilo principal y avisa a la UI. */
    fun post(block: DownloadState.() -> Unit = {}) {
        main.post {
            block()
            listeners.toList().forEach { it() }
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = sp.getString("tree_uri", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("tree_uri", v?.toString()).apply()

    var format: Format
        get() = runCatching { Format.valueOf(sp.getString("format", "MP3")!!) }.getOrDefault(Format.MP3)
        set(v) = sp.edit().putString("format", v.name).apply()

    var qualityMp3: Int
        get() = sp.getInt("q_mp3", 0)
        set(v) = sp.edit().putInt("q_mp3", v).apply()

    var qualityMp4: Int
        get() = sp.getInt("q_mp4", 1)
        set(v) = sp.edit().putInt("q_mp4", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update", 0)
        set(v) = sp.edit().putLong("last_update", v).apply()

    var ytdlpVersion: String?
        get() = sp.getString("ytdlp_version", null)
        set(v) = sp.edit().putString("ytdlp_version", v).commit().let { }

    var bundledFor: Long
        get() = sp.getLong("bundled_for", -1)
        set(v) = sp.edit().putLong("bundled_for", v).commit().let { }

    // ---- Ajustes de velocidad
    var speedMode: SpeedMode
        get() = runCatching { SpeedMode.valueOf(sp.getString("speed_mode", "FAST")!!) }.getOrDefault(SpeedMode.FAST)
        set(v) = sp.edit().putString("speed_mode", v.name).apply()

    var ultraWifiOnly: Boolean
        get() = sp.getBoolean("ultra_wifi_only", true)
        set(v) = sp.edit().putBoolean("ultra_wifi_only", v).apply()

    var heatProtect: Boolean
        get() = sp.getBoolean("heat_protect", true)
        set(v) = sp.edit().putBoolean("heat_protect", v).apply()

    var batteryPromptShown: Boolean
        get() = sp.getBoolean("battery_prompt", false)
        set(v) = sp.edit().putBoolean("battery_prompt", v).apply()

    // ---- Estadísticas
    var statSongs: Int
        get() = sp.getInt("stat_songs", 0)
        set(v) = sp.edit().putInt("stat_songs", v).apply()

    var statBytes: Long
        get() = sp.getLong("stat_bytes", 0)
        set(v) = sp.edit().putLong("stat_bytes", v).apply()

    var statSavedSec: Long
        get() = sp.getLong("stat_saved", 0)
        set(v) = sp.edit().putLong("stat_saved", v).apply()

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Descargas por día ("2026-11-15:12;2026-11-14:3"), se guardan los últimos 14 días. */
    fun addDaily(count: Int) {
        val map = dailyMap().toMutableMap()
        val today = dayFmt.format(Date())
        map[today] = (map[today] ?: 0) + count
        val keep = map.keys.sortedDescending().take(14).toSet()
        sp.edit().putString("stat_daily",
            map.filterKeys { it in keep }.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()
    }

    private fun dailyMap(): Map<String, Int> =
        (sp.getString("stat_daily", "") ?: "").split(";").mapNotNull {
            val p = it.split(":"); if (p.size == 2) p[0] to (p[1].toIntOrNull() ?: 0) else null
        }.toMap()

    /** Últimos 7 días (del más antiguo a hoy): pares (inicial del día, cantidad). */
    fun last7Days(): List<Pair<String, Int>> {
        val map = dailyMap()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        val letters = arrayOf("D", "L", "M", "X", "J", "V", "S")
        return (0 until 7).map {
            val key = dayFmt.format(cal.time)
            val label = letters[cal.get(Calendar.DAY_OF_WEEK) - 1]
            cal.add(Calendar.DAY_OF_YEAR, 1)
            label to (map[key] ?: 0)
        }
    }
}

object Quality {
    val MP3_LABELS = listOf("Máxima", "320 kbps", "192 kbps", "128 kbps")
    val MP3_ARGS = listOf("0", "320K", "192K", "128K")

    val MP4_LABELS = listOf("Mejor disponible", "1080p", "720p", "480p", "360p")
    val MP4_HEIGHT = listOf(0, 1080, 720, 480, 360)
}

object Util {
    fun fmtDuration(sec: Double?): String {
        if (sec == null || sec <= 0) return ""
        val s = sec.toLong()
        val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, r)
        else String.format(Locale.US, "%d:%02d", m, r)
    }

    fun fmtEta(sec: Long): String = if (sec < 0) "--:--" else fmtDuration(sec.toDouble()).ifEmpty { "0:00" }

    /** "quedan ~6 min" */
    fun fmtRemaining(sec: Long): String = when {
        sec < 0 -> "calculando…"
        sec < 60 -> "menos de 1 min"
        sec < 3600 -> "~${(sec + 30) / 60} min"
        else -> String.format(Locale.US, "~%d h %02d min", sec / 3600, (sec % 3600) / 60)
    }

    fun fmtSpeed(bps: Double): String = when {
        bps <= 0 -> "—"
        bps < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bps / 1024)
        else -> String.format(Locale.US, "%.1f MB/s", bps / 1024 / 1024)
    }

    fun fmtBytes(b: Long): String = when {
        b < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.0f MB", b / 1024.0 / 1024)
        else -> String.format(Locale.US, "%.2f GB", b / 1024.0 / 1024 / 1024)
    }

    fun fmtHours(sec: Long): String = when {
        sec < 60 -> "${sec} s"
        sec < 3600 -> "${sec / 60} min"
        else -> String.format(Locale.US, "%.1f h", sec / 3600.0)
    }

    private val speedRe = Regex("""at\s+~?\s*([\d.]+)\s*([KMG]?i?B)/s""")

    /** Velocidad en bytes/s a partir de una línea de progreso de yt-dlp. */
    fun parseSpeed(line: String): Double? {
        val m = speedRe.find(line) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].uppercase(Locale.ROOT)) {
            "KIB", "KB" -> 1024.0
            "MIB", "MB" -> 1024.0 * 1024
            "GIB", "GB" -> 1024.0 * 1024 * 1024
            else -> 1.0
        }
        return n * mult
    }

    /** ¿YouTube está pidiendo "confirma que no eres un robot"? */
    fun isBotCheck(err: String?): Boolean {
        val e = err?.lowercase() ?: return false
        return listOf("not a bot", "confirm you", "sign in to confirm", "http error 429",
            "too many requests", "protect our community").any { it in e }
    }

    /** Extrae la primera URL http(s) de un texto (p. ej. lo que comparte la app de YouTube). */
    fun extractUrl(text: String?): String? =
        text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd('.', ',', ')') }

    /** Convierte la salida de `yt-dlp --flat-playlist -J` en una lista de videos. */
    fun parseInfo(json: String): Triple<String, Boolean, List<VideoItem>> {
        val o = JSONObject(json)
        val entries = o.optJSONArray("entries")
        if (entries == null) {
            val id = o.optString("id")
            val url = o.optString("webpage_url").ifEmpty { o.optString("original_url") }
            val item = VideoItem(id, o.optString("title", id), url,
                o.optDouble("duration").takeIf { !it.isNaN() })
            return Triple(item.title, false, listOf(item))
        }
        val list = ArrayList<VideoItem>(entries.length())
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.optString("id")
            if (id.isEmpty()) continue
            var url = e.optString("url")
            if (!url.startsWith("http")) url = "https://www.youtube.com/watch?v=$id"
            val title = e.optString("title").ifEmpty { id }
            val unavailable = title == "[Private video]" || title == "[Deleted video]"
            list += VideoItem(id, title, url, e.optDouble("duration").takeIf { !it.isNaN() },
                checked = !unavailable)
        }
        return Triple(o.optString("title", "Playlist"), true, list)
    }

    /** "content://…/tree/primary%3AMusic%2FYT" → "Almacenamiento interno/Music/YT" */
    fun prettyTree(uri: Uri): String {
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull() ?: return uri.toString()
        val parts = docId.split(":", limit = 2)
        val vol = if (parts[0] == "primary") "Almacenamiento interno" else "Tarjeta SD (${parts[0]})"
        return if (parts.size > 1 && parts[1].isNotEmpty()) "$vol/${parts[1]}" else vol
    }
}
