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

class App : Application() {

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
        private const val TAG = "YTMP3"

        /** Cada cuánto se comprueba si hay un yt-dlp nuevo antes de buscar/descargar. */
        const val UPDATE_EVERY_MS = 6L * 60 * 60 * 1000

        lateinit var instance: App
            private set

        /** Se completa cuando Python + yt-dlp + ffmpeg están listos (la 1ª vez tarda unos segundos). */
        val ready = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
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
    }
}
