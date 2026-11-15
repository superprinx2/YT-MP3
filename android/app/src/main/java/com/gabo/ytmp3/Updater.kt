package com.gabo.ytmp3

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mantiene yt-dlp al día. YouTube cambia su protección cada pocas semanas y una
 * versión vieja de yt-dlp provoca "HTTP Error 403: Forbidden".
 *
 * No usa la API de GitHub (límite de 60 consultas/hora por IP, que con datos móviles
 * se agota enseguida porque muchos usuarios comparten IP): lee la redirección de
 * github.com/yt-dlp/yt-dlp/releases/latest y descarga el binario directamente.
 */
object Updater {
    /** Versión de yt-dlp incluida en res/raw/ytdlp de esta APK. */
    const val BUNDLED = "2026.10.26"

    private const val TAG = "YTMP3"
    private const val UA = "YT-MP3-Android/1.1"
    private val mutex = Mutex()

    private fun ytdlpDir(ctx: Context) = File(File(ctx.noBackupFilesDir, "youtubedl-android"), "yt-dlp")
    private fun ytdlpFile(ctx: Context) = File(ytdlpDir(ctx), "yt-dlp")

    /**
     * La librería solo copia el yt-dlp incluido si no existe ya uno instalado; así que tras
     * instalar una APK nueva borramos el anterior si es más viejo que el incluido.
     * Debe llamarse ANTES de YoutubeDL.init().
     */
    fun prepareBundled(ctx: Context) {
        val p = Prefs(ctx)
        val vc = PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
        if (p.bundledFor == vc) return
        val installed = p.ytdlpVersion
        if (installed == null || installed < BUNDLED || !ytdlpFile(ctx).exists()) {
            ytdlpDir(ctx).deleteRecursively()
            p.ytdlpVersion = BUNDLED
        }
        p.bundledFor = vc
    }

    fun currentVersion(ctx: Context): String = Prefs(ctx).ytdlpVersion ?: BUNDLED

    /**
     * Busca actualización si la última comprobación es más vieja que [maxAgeMs].
     * Devuelve la versión nueva si actualizó, o null. Nunca lanza excepción.
     */
    suspend fun ensureFresh(ctx: Context, maxAgeMs: Long, timeoutMs: Long = 60_000): String? =
        mutex.withLock {
            val p = Prefs(ctx)
            if (System.currentTimeMillis() - p.lastUpdateCheck < maxAgeMs) return@withLock null
            withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    try { update(ctx) } catch (e: Throwable) {
                        Log.w(TAG, "update failed", e); null
                    }
                }
            }
        }

    /** Actualización manual (menú). Lanza excepción si falla. */
    suspend fun updateNow(ctx: Context): String? = mutex.withLock {
        runInterruptible(Dispatchers.IO) { update(ctx) }
    }

    private fun update(ctx: Context): String? {
        val p = Prefs(ctx)
        val tag = latestTag()
        if (tag == p.ytdlpVersion && ytdlpFile(ctx).exists()) {
            p.lastUpdateCheck = System.currentTimeMillis()
            return null
        }
        val tmp = File(ctx.cacheDir, "yt-dlp.download")
        try {
            download("https://github.com/yt-dlp/yt-dlp/releases/download/$tag/yt-dlp", tmp)
            val head = tmp.inputStream().use { s -> ByteArray(2).also { s.read(it) } }
            if (tmp.length() < 1_000_000 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) {
                throw IOException("Archivo descargado no válido")
            }
            val dst = ytdlpFile(ctx)
            dst.parentFile?.mkdirs()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
            }
        } finally {
            tmp.delete()
        }
        p.ytdlpVersion = tag
        p.lastUpdateCheck = System.currentTimeMillis()
        Log.i(TAG, "yt-dlp actualizado a $tag")
        return tag
    }

    private fun latestTag(): String {
        val c = URL("https://github.com/yt-dlp/yt-dlp/releases/latest").openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            val loc = c.getHeaderField("Location") ?: throw IOException("Sin redirección (HTTP ${c.responseCode})")
            val tag = loc.substringAfter("/tag/", "").trim('/')
            if (!Regex("""\d{4}\.\d{2}\.\d{2}(\.\d+)?""").matches(tag)) throw IOException("Versión inesperada: $tag")
            return tag
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dst: File) {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", UA)
                when (val code = c.responseCode) {
                    in 300..399 -> { u = URL(u, c.getHeaderField("Location")); return@repeat }
                    200 -> { c.inputStream.use { i -> dst.outputStream().use { i.copyTo(it, 64 * 1024) } }; return }
                    else -> throw IOException("HTTP $code al descargar yt-dlp")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Demasiadas redirecciones")
    }
}
