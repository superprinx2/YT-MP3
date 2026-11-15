package com.gabo.ytmp3

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlin.math.max
import kotlin.math.min

/** Lo que el celular aguanta: memoria, núcleos, temperatura, batería y tipo de red. */
object Device {

    /** Máximo de descargas simultáneas que tiene sentido en este celular (modo Ultra). */
    fun maxParallel(ctx: Context): Int {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val gb = mi.totalMem / 1_000_000_000.0
        // Cada yt-dlp es un proceso de Python (~80-120 MB) y la conversión a MP3 usa CPU.
        val byRam = when {
            gb < 2.5 -> 2
            gb < 3.5 -> 3
            gb < 5.0 -> 4
            gb < 7.0 -> 6
            else -> 8
        }
        val cores = Runtime.getRuntime().availableProcessors()
        return min(byRam, max(2, cores)).coerceIn(2, 8)
    }

    fun limitFor(ctx: Context, mode: SpeedMode): Int = when (mode) {
        SpeedMode.NORMAL -> 2
        SpeedMode.FAST -> min(4, maxParallel(ctx))
        SpeedMode.ULTRA -> maxParallel(ctx)
    }

    fun isUnmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    data class Battery(val tempC: Float?, val pct: Int, val charging: Boolean)

    fun battery(ctx: Context): Battery {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(
            tempC = if (t == Int.MIN_VALUE) null else t / 10f,
            pct = if (level >= 0 && scale > 0) level * 100 / scale else 100,
            charging = charging,
        )
    }

    /** Estado térmico del sistema (Android 10+): 0 normal … 3 severo … 6 apagado. */
    fun thermal(ctx: Context): Int =
        if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(PowerManager::class.java).currentThermalStatus else 0

    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
}
