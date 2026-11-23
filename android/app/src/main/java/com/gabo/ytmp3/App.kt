package com.gabo.ytmp3

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application(), coil.ImageLoaderFactory {

    /**
     * Portadas en bitmaps normales (no de "hardware"): la barra de cristal (BlurView) dibuja la pantalla por
     * software y los bitmaps de hardware la hacían cerrarse ("Software rendering doesn't support hardware bitmaps").
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this).allowHardware(false).crossfade(true).build()


    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
        const val CHANNEL_ALERT = "alert"
        const val CHANNEL_PLAYBACK = "playback"
        private const val TAG = "YTMP3"

        /** Cada cuánto se comprueba si hay un yt-dlp nuevo antes de buscar/descargar. */
        const val UPDATE_EVERY_MS = 6L * 60 * 60 * 1000

        lateinit var instance: App
            private set

        /** Se completa cuando Python + yt-dlp + ffmpeg están listos (la 1ª vez tarda unos segundos). */
        val ready = CompletableDeferred<Unit>()
        /** Trabajos en segundo plano: un error inesperado se anota en el informe en vez de cerrar la app. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "error en segundo plano", e)
            runCatching { BlackBox.log(instance, "error en segundo plano: ${e.javaClass.simpleName}: ${e.message?.take(160)}") }
        })

        /** Caché de yt-dlp (desafíos JS de YouTube ya resueltos, etc.). */
        fun cacheDir(ctx: android.content.Context) = java.io.File(ctx.filesDir, "ytdlp-cache").apply { mkdirs() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Proceso auxiliar de análisis: no se inicia nada más (ni yt-dlp, ni el analizador, ni contadores)
        if (AnalyzerRemote.isAnaProcess(this)) return
        Prefs2.applyTheme(this)
        createChannels()
        // Registro de errores: si la app se cierra, se guarda el detalle para enviarlo después
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            runCatching {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash_last.txt").writeText("Hilo: ${th.name}\n$sw")
                BlackBox.log(this, "la app se cerró: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            }
            prev?.uncaughtException(th, e)
        }
        // Modo seguro: si se cerró 2 veces seguidas al arrancar, esta vez no se analiza nada
        val boot = getSharedPreferences("boot", MODE_PRIVATE)
        val fails = boot.getInt("fails", 0)
        boot.edit().putInt("fails", fails + 1).commit()
        if (fails >= 2) BlackBox.log(this, "modo seguro: análisis en pausa en este inicio")
        else Analyzer.kick(this)
        scope.launch {
            try {
                Updater.prepareBundled(this@App)
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                ready.complete(Unit)
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                ready.completeExceptionally(e)
                return@launch
            }
            // Comprobación en segundo plano al abrir; buscar/descargar vuelven a comprobar y esperan.
            if (!DownloadState.running) Updater.ensureFresh(this@App, UPDATE_EVERY_MS)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Progreso de descargas", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Descargas terminadas", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, "Reproducción de música", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                description = "Controles de la canción en la notificación y la pantalla de bloqueo"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Avisos (verificación, calor)", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
