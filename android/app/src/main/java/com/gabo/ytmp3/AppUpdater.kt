package com.gabo.ytmp3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualiza la propia app desde GitHub Releases: compara la última versión publicada con la
 * instalada, descarga la APK adecuada para el procesador del teléfono y abre el instalador.
 * Las APK están firmadas con la misma clave, así que se instalan encima sin perder datos.
 */
object AppUpdater {
    private const val REPO = "https://github.com/superprinx2/YT-MP3"
    private const val UA = "MegaDown-Android"
    private const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }

    /** ¿a es más nueva que b? */
    fun isNewer(a: String, b: String): Boolean {
        val x = parse(a); val y = parse(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** Última versión publicada (p. ej. "1.7"), leyendo la redirección de releases/latest. */
    fun latestVersion(): String {
        val c = URL("$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            val loc = c.getHeaderField("Location") ?: throw IOException("GitHub no respondió (HTTP ${c.responseCode})")
            return loc.substringAfter("/tag/").trim('/').removePrefix("v")
        } finally { c.disconnect() }
    }

    /** APK adecuada para el procesador del teléfono. */
    private fun assetName(): String {
        val abis = Build.SUPPORTED_ABIS.toList()
        return when {
            "arm64-v8a" in abis -> "MegaDown-arm64.apk"
            "armeabi-v7a" in abis -> "MegaDown-armv7.apk"
            else -> "MegaDown-universal.apk"
        }
    }

    private fun download(url: String, dst: File, onProgress: (Int) -> Unit) {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", UA)
                when (val code = c.responseCode) {
                    in 300..399 -> { u = URL(u, c.getHeaderField("Location")); return@repeat }
                    200 -> {
                        val total = c.contentLengthLong
                        var done = 0L
                        val buf = ByteArray(128 * 1024)
                        c.inputStream.use { input ->
                            dst.outputStream().use { out ->
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) onProgress((done * 100 / total).toInt())
                                }
                            }
                        }
                        return
                    }
                    else -> throw IOException("HTTP $code al descargar la actualización")
                }
            } finally { c.disconnect() }
        }
        throw IOException("Demasiadas redirecciones")
    }

    /**
     * Comprueba si hay versión nueva. [manual] = el usuario tocó "Buscar actualización"
     * (muestra "ya tienes la última" y no respeta el intervalo).
     */
    fun check(activity: AppCompatActivity, manual: Boolean) {
        val prefs = activity.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("app_update_check", 0) < CHECK_EVERY_MS) return
        if (manual) Toast.makeText(activity, "Buscando actualización de MegaDown…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val latest = try {
                withContext(Dispatchers.IO) { latestVersion() }
            } catch (e: Exception) {
                if (manual) Toast.makeText(activity, "No se pudo consultar GitHub: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            prefs.edit().putLong("app_update_check", now).apply()
            val current = currentVersion(activity)
            if (!isNewer(latest, current)) {
                if (manual) Toast.makeText(activity, "Ya tienes la última versión ($current) ✓", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!manual && prefs.getString("app_update_skipped", null) == latest) return@launch
            if (activity.isFinishing) return@launch
            MaterialAlertDialogBuilder(activity)
                .setTitle("¡MegaDown $latest disponible! 🚀")
                .setMessage("Tienes la versión $current. La nueva se instala encima, sin perder tus ajustes.")
                .setPositiveButton("Actualizar ahora") { _, _ -> install(activity, latest) }
                .setNegativeButton("Más tarde") { _, _ -> prefs.edit().putString("app_update_skipped", latest).apply() }
                .show()
        }
    }

    private fun install(activity: AppCompatActivity, version: String) {
        // Android 8+: hay que permitir que MegaDown instale apps (una sola vez).
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Permite «Instalar apps desconocidas» para MegaDown y vuelve a tocar Actualizar.",
                Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")))
            activity.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().remove("app_update_check").apply()
            return
        }
        val bar = LinearProgressIndicator(activity).apply { max = 100; isIndeterminate = true }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Descargando MegaDown $version…")
            .setView(bar.also { it.setPadding(60, 40, 60, 10) })
            .setCancelable(false)
            .show()
        val dir = File(activity.cacheDir, "apk").apply { mkdirs() }
        val apk = File(dir, "MegaDown-$version.apk")
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    dir.listFiles()?.forEach { it.delete() }
                    download("$REPO/releases/download/v$version/${assetName()}", apk) { p ->
                        bar.post { bar.isIndeterminate = false; bar.setProgressCompat(p, true) }
                    }
                }
                dialog.dismiss()
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
                activity.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(activity, "No se pudo descargar la actualización: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
