package com.gabo.ytmp3

import android.content.ComponentName
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Que las descargas sigan con el celular bloqueado. El servicio ya es "en primer plano" y mantiene la CPU y el Wi-Fi
 * despiertos, pero Android (y sobre todo Huawei/Honor) congela o cierra la app si no tiene permiso de batería.
 */
object Background {
    val huawei: Boolean get() = android.os.Build.MANUFACTURER.lowercase().let { it.contains("huawei") || it.contains("honor") }

    fun openHuaweiStartup(a: AppCompatActivity): Boolean {
        val comps = listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        for ((pkg, cls) in comps) {
            if (runCatching { a.startActivity(Intent().setComponent(ComponentName(pkg, cls))) }.isSuccess) return true
        }
        return false
    }

    /**
     * Antes de una descarga: si falta el permiso de batería (o en Huawei, la primera vez, "Gestionar manualmente"),
     * se explica y se lleva al ajuste. [then] sigue con la descarga pase lo que pase.
     */
    fun beforeDownload(a: AppCompatActivity, count: Int, then: () -> Unit) = ask(a, count >= 3, false, then)

    /** Al empezar a escuchar: sin este permiso Huawei corta el audio a los pocos minutos con el celular bloqueado. */
    fun beforePlayback(a: AppCompatActivity) = ask(a, true, true) {}

    private fun ask(a: AppCompatActivity, worth: Boolean, music: Boolean, then: () -> Unit) {
        val sp = a.getSharedPreferences("background", 0)
        val exempt = Device.ignoresBatteryOptimizations(a)
        val needHuawei = huawei && !sp.getBoolean("huawei_done", false)
        val snoozed = System.currentTimeMillis() < sp.getLong("snooze_until", 0L)
        if ((exempt && !needHuawei) || !worth || snoozed) { then(); return }
        val steps = buildString {
            append(if (music) "Para que la música no se corte con el celular bloqueado:" else "Para que las descargas sigan con el celular bloqueado:")
            if (!exempt) append("\n\n1. Permite que DownSong funcione sin restricciones de batería.")
            if (huawei) append("\n\n${if (exempt) "1" else "2"}. Huawei: Ajustes › Batería › Inicio de aplicaciones › DownSong › " +
                "desactiva «Gestionar automáticamente» y activa las 3 opciones (inicio automático, inicio secundario y " +
                "ejecución en segundo plano).")
            append(if (music) "\n\nSi no, el sistema le quita el audio a la app a los pocos minutos de bloquear el celular."
                   else "\n\nSi no, el sistema congela la app al bloquear el celular y la descarga se detiene.")
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(if (music) "Música con el celular bloqueado" else "Descargar con el celular bloqueado")
            .setMessage(steps)
            .setPositiveButton("Configurar") { _, _ ->
                if (!exempt) SettingsActivity.requestBatteryExemption(a)
                if (huawei) {
                    sp.edit().putBoolean("huawei_done", true).apply()
                    a.window.decorView.postDelayed({ openHuaweiStartup(a) }, if (exempt) 0 else 1500)
                }
                then()
            }
            .setNegativeButton("Ahora no") { _, _ ->
                sp.edit().putLong("snooze_until", System.currentTimeMillis() + 24 * 3600_000L).apply()
                then()
            }
            .setCancelable(false)
            .show()
    }
}
