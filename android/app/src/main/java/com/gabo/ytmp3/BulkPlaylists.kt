package com.gabo.ytmp3

import android.content.Context
import android.webkit.CookieManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * "Descargar todas mis playlists" de YouTube o Spotify (con la sesión iniciada en la réplica).
 * Lee la lista de playlists, deja elegir cuáles y junta TODAS sus canciones (sin el tope de 100).
 */
object BulkPlaylists {

    class Group(val name: String, val items: List<VideoItem>)

    fun loggedIn(p: Platform): Boolean {
        val cm = CookieManager.getInstance()
        return when (p) {
            Platform.YOUTUBE -> listOf("https://www.youtube.com", "https://m.youtube.com")
                .any { (cm.getCookie(it) ?: "").let { c -> "SAPISID" in c || "LOGIN_INFO" in c } }
            // solo se mira si existe la sesión; la app nunca lee ni usa su valor
            Platform.SPOTIFY -> cm.getCookie("https://open.spotify.com")?.contains("sp_dc=") == true
            else -> false
        }
    }

    /** Ventanita de avance con "Cancelar". */
    class Progress(ctx: Context, title: String) {
        @Volatile var cancelled = false
        private val text = TextView(ctx).apply {
            setPadding(ctx.dp(24), ctx.dp(12), ctx.dp(24), ctx.dp(4)); textSize = 15f
            setTextColor(ctx.col(R.color.ios_label))
        }
        private val dialog: AlertDialog = MaterialAlertDialogBuilder(ctx).setTitle(title).setView(text)
            .setCancelable(false).setNegativeButton("Cancelar") { _, _ -> cancelled = true }.show()
        fun set(s: String) { text.text = s }
        fun close() = runCatching { dialog.dismiss() }
    }

    // ------------------------------------------------------------------ YouTube
    private fun ytJson(ctx: Context, url: String): String {
        val cookies = Cookies.tempCopy(ctx)
        try {
            val req = YoutubeDLRequest(url).addOption("--flat-playlist").addOption("-J").addOption("--no-warnings")
                .addOption("--cache-dir", App.cacheDir(ctx).absolutePath)
            if (cookies != null) req.addOption("--cookies", cookies.absolutePath)
            return YoutubeDL.getInstance().execute(req).out
        } finally { cookies?.delete() }
    }

    /** Playlists de "Tú › Playlists" (creadas y guardadas) + "Videos que me gustan". */
    suspend fun youtubeLists(ctx: Context): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        App.ready.await()
        Cookies.exportFromWebView(ctx)
        val o = JSONObject(ytJson(ctx, "https://www.youtube.com/feed/playlists"))
        val arr = o.optJSONArray("entries")
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("id").ifEmpty {
                Regex("list=([\\w-]+)").find(e.optString("url"))?.groupValues?.get(1).orEmpty()
            }
            if (id.isEmpty() || !seen.add(id)) continue
            out += e.optString("title").ifEmpty { "Playlist de YouTube" } to "https://www.youtube.com/playlist?list=$id"
        }
        if (seen.add("LL")) out += "Videos que me gustan" to "https://www.youtube.com/playlist?list=LL"
        out
    }

    suspend fun youtubeItems(ctx: Context, url: String): List<VideoItem> = withContext(Dispatchers.IO) {
        Util.parseInfo(ytJson(ctx, url), Platform.YOUTUBE).third
    }

    // ------------------------------------------------------------------ Spotify (en la réplica, hilo principal)
    suspend fun spotifyLists(web: WebReplica): List<Pair<String, String>> =
        withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { k -> web.collectSpotifyLibrary { if (k.isActive) k.resume(it) } }
        } ?: run { web.cancelCollect(); emptyList() }

    suspend fun spotifyItems(web: WebReplica, url: String): List<VideoItem> =
        withTimeoutOrNull(90_000) {
            suspendCancellableCoroutine { k -> web.openAndCollect(url) { _, items -> if (k.isActive) k.resume(items) } }
        } ?: run { web.cancelCollect(); emptyList() }

    /** Paso final del asistente: cuántas playlists hay, cuántas canciones tiene cada una, y elegir cuáles bajar. */
    suspend fun choose(ctx: Context, p: Platform, groups: List<Group>): List<Group>? =
        suspendCancellableCoroutine { k ->
            val usable = groups.filter { it.items.isNotEmpty() }
            val checked = BooleanArray(usable.size) { true }
            val total = usable.sumOf { it.items.size }
            val labels = usable.map { g -> "${g.name}  ·  ${g.items.size} ${if (g.items.size == 1) "canción" else "canciones"}" }
            val empty = groups.size - usable.size
            MaterialAlertDialogBuilder(ctx)
                .setTitle("${usable.size} playlists de ${p.label} · $total canciones" + if (empty > 0) " ($empty vacías)" else "")
                .setMultiChoiceItems(labels.toTypedArray(), checked) { _, w, v -> checked[w] = v }
                .setPositiveButton("Descargar marcadas") { _, _ -> if (k.isActive) k.resume(usable.filterIndexed { i, _ -> checked[i] }) }
                .setNegativeButton("Cancelar") { _, _ -> if (k.isActive) k.resume(null) }
                .setOnCancelListener { if (k.isActive) k.resume(null) }
                .show()
        }
}
