package com.gabo.ytmp3

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File

/**
 * Cuando YouTube pide "confirma que no eres un robot", el usuario lo resuelve en una
 * página de YouTube dentro de la app (VerifyActivity). Guardamos las cookies de esa
 * sesión en formato Netscape para que yt-dlp las use (--cookies). Solo quedan en el teléfono.
 */
object Cookies {
    private const val FAR_FUTURE = 2_147_483_647L

    fun file(ctx: Context) = File(ctx.filesDir, "cookies.txt")

    fun has(ctx: Context) = file(ctx).let { it.exists() && it.length() > 0 }

    /** Exporta las cookies del WebView. Devuelve cuántas se guardaron. */
    fun exportFromWebView(ctx: Context): Int {
        val cm = CookieManager.getInstance()
        cm.flush()
        val sources = listOf(
            ".youtube.com" to "https://www.youtube.com",
            ".youtube.com" to "https://m.youtube.com",
            ".google.com" to "https://www.google.com",
            ".google.com" to "https://accounts.google.com",
        )
        val seen = HashSet<String>()
        val lines = ArrayList<String>()
        for ((domain, url) in sources) {
            val raw = cm.getCookie(url) ?: continue
            raw.split(";").map { it.trim() }.filter { it.contains('=') }.forEach { kv ->
                val name = kv.substringBefore('=').trim()
                val value = kv.substringAfter('=').trim()
                if (name.isNotEmpty() && seen.add("$domain|$name")) {
                    lines += listOf(domain, "TRUE", "/", "TRUE", FAR_FUTURE.toString(), name, value)
                        .joinToString("\t")
                }
            }
        }
        if (lines.isEmpty()) return 0
        file(ctx).writeText("# Netscape HTTP Cookie File\n# Generado por YT MP3\n" + lines.joinToString("\n") + "\n")
        return lines.size
    }

    /** Importa un cookies.txt (formato Netscape) exportado desde un navegador. */
    fun import(ctx: Context, uri: Uri): Int {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalArgumentException("No se pudo leer el archivo")
        val valid = text.lines().filter { !it.startsWith("#") && it.split("\t").size >= 7 }
        if (valid.none { it.contains("youtube.com") || it.contains("google.com") }) {
            throw IllegalArgumentException("El archivo no contiene cookies de YouTube")
        }
        file(ctx).writeText(text)
        return valid.size
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** Copia temporal para cada proceso (yt-dlp reescribe el archivo al terminar). */
    fun tempCopy(ctx: Context): File? {
        if (!has(ctx)) return null
        val t = File(ctx.cacheDir, "ck-${System.nanoTime()}.txt")
        file(ctx).copyTo(t, overwrite = true)
        return t
    }
}
