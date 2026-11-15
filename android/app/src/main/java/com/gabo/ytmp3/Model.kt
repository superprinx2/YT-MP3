package com.gabo.ytmp3

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Locale

enum class Format(val ext: String, val mime: String) {
    MP3("mp3", "audio/mpeg"),
    MP4("mp4", "video/mp4")
}

enum class Status { PENDING, DOWNLOADING, DONE, ERROR }

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
}

/**
 * Estado compartido entre la Activity y el servicio de descarga.
 * Todas las escrituras se hacen en el hilo principal (vía [post]).
 */
object DownloadState {
    val items = mutableListOf<VideoItem>()
    var listTitle: String? = null
    var isPlaylist = false

    @Volatile var running = false
    var current: VideoItem? = null
    var phase = ""
    var progress = 0f          // 0..100 del video actual
    var etaSec = -1L
    var speed = ""
    var doneCount = 0
    var errorCount = 0
    var totalCount = 0
    var finishedMessage: String? = null

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
