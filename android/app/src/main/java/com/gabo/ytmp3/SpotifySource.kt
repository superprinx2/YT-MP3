package com.gabo.ytmp3

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spotify: su audio está protegido (DRM) y no se descarga. Lo que hacemos es leer la lista
 * de canciones (título, artista y duración) desde la página pública "embed" de Spotify y
 * luego buscar cada canción en YouTube, comprobando la duración para elegir la versión correcta.
 *
 * La página embed muestra como máximo 100 canciones por playlist.
 */
object SpotifySource {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    const val MAX_TRACKS = 100

    data class Ref(val kind: String, val id: String)

    private val refRe = Regex("""open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]+)""")

    fun parse(url: String): Ref? = refRe.find(url)?.let { Ref(it.groupValues[1], it.groupValues[2]) }

    /** Enlaces cortos (spotify.link / spoti.fi): se lee la página de destino. */
    private fun resolve(url: String): Ref {
        parse(url)?.let { return it }
        val html = get(url)
        return parse(html) ?: throw IOException("No reconozco ese enlace de Spotify")
    }

    private fun get(url: String): String {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                when (val code = c.responseCode) {
                    in 300..399 -> { u = URL(u, c.getHeaderField("Location")); return@repeat }
                    200 -> return c.inputStream.use { it.readBytes().decodeToString() }
                    404 -> throw IOException("Spotify no encontró esa playlist (¿es privada o fue borrada?)")
                    else -> throw IOException("Spotify respondió HTTP $code")
                }
            } finally { c.disconnect() }
        }
        throw IOException("Demasiadas redirecciones")
    }

    private fun firstUrl(arr: JSONArray?): String? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("url")?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return null
    }

    /** Devuelve (nombre, ¿es lista?, canciones). */
    fun fetch(url: String): Triple<String, Boolean, List<VideoItem>> {
        val ref = resolve(url)
        val html = get("https://open.spotify.com/embed/${ref.kind}/${ref.id}")
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IOException("Spotify cambió su página; actualiza la app")
        val entity = JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity")

        val cover = firstUrl(entity.optJSONObject("visualIdentity")?.optJSONArray("image"))
            ?: firstUrl(entity.optJSONObject("coverArt")?.optJSONArray("sources"))
        val name = entity.optString("name").ifEmpty { entity.optString("title", "Spotify") }

        if (ref.kind == "track") {
            val artists = entity.optJSONArray("artists")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(", ")
            }.orEmpty()
            val item = track(ref.id, entity.optString("title", name), artists, entity.optLong("duration"), cover)
            return Triple(item.title, false, listOf(item))
        }

        val list = entity.optJSONArray("trackList") ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("uri").substringAfterLast(':').ifEmpty { "sp$i" }
            track(id, t.optString("title"), t.optString("subtitle"), t.optLong("duration"), cover)
                .also { it.checked = t.optBoolean("isPlayable", true) }
        }
        return Triple(name, true, items)
    }

    private fun track(id: String, title: String, artists: String, durMs: Long, cover: String?): VideoItem {
        // Spotify separa artistas con espacios "no separables" (U+00A0): normalizarlos.
        @Suppress("NAME_SHADOWING") val artists = artists.replace('\u00A0', ' ')
        @Suppress("NAME_SHADOWING") val title = title.replace('\u00A0', ' ')
        val mainArtist = artists.substringBefore(",").trim()
        val display = if (artists.isNotEmpty()) "$artists - $title" else title
        // La "url" guarda la búsqueda; el servicio la convierte en "ytsearch".
        val query = if (mainArtist.isNotEmpty()) "$mainArtist - $title" else title
        return VideoItem(id, display, "spotify:$query", if (durMs > 0) durMs / 1000.0 else null,
            platform = Platform.SPOTIFY, thumbUrl = cover, uploader = mainArtist)
    }
}
