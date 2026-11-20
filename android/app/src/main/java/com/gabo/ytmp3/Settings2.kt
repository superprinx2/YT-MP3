package com.gabo.ytmp3

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class UsageMode(val label: String) { MAX("Máximo"), BALANCED("Equilibrado"), SAVER("Ahorro") }

enum class AppTheme(val label: String) { LIGHT("Claro"), DARK("Oscuro"), SYSTEM("Sistema"), DYNAMIC("Dinámico") }

/** Ajustes de DownSong 2 (reproductor, apariencia, perfil). */
class Prefs2(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val sp = ctx.applicationContext.getSharedPreferences("prefs2", Context.MODE_PRIVATE)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var onboarded: Boolean get() = b("onboarded", false); set(v) = setB("onboarded", v)
    var nickname: String get() = sp.getString("nick", "") ?: ""; set(v) = sp.edit().putString("nick", v).apply()
    var photoPath: String? get() = sp.getString("photo", null); set(v) = sp.edit().putString("photo", v).apply()

    var crossfadeOn: Boolean get() = b("cf_on", true); set(v) = setB("cf_on", v)
    var crossfadeSec: Int get() = sp.getInt("cf_sec", 5).coerceIn(1, 12); set(v) = sp.edit().putInt("cf_sec", v).apply()
    var normalize: Boolean get() = b("normalize", true); set(v) = setB("normalize", v)

    var theme: AppTheme
        get() = runCatching { AppTheme.valueOf(sp.getString("theme", "DARK")!!) }.getOrDefault(AppTheme.DARK)
        set(v) = sp.edit().putString("theme", v.name).apply()
    var kaleidoNoCover: Boolean get() = b("k_nocover", true); set(v) = setB("k_nocover", v)
    var kaleidoAll: Boolean get() = b("k_all", false); set(v) = setB("k_all", v)
    var lockButtons: Boolean get() = b("lock_btns", true); set(v) = setB("lock_btns", v)

    var shuffle: Boolean get() = b("shuffle", false); set(v) = setB("shuffle", v)

    var usage: UsageMode
        get() = runCatching { UsageMode.valueOf(sp.getString("usage", null)!!) }.getOrElse { defaultUsage() }
        set(v) = sp.edit().putString("usage", v.name).apply()
    private fun defaultUsage(): UsageMode {
        val am = appCtx.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val m = if (mi.totalMem <= 3_300_000_000L || am.isLowRamDevice) UsageMode.SAVER else UsageMode.BALANCED
        sp.edit().putString("usage", m.name).apply()
        return m
    }
    val saver get() = usage == UsageMode.SAVER
    var lockScreen: Boolean get() = b("lock_own", true); set(v) = setB("lock_own", v)
    var skipSilence: Boolean get() = b("skip_sil", true); set(v) = setB("skip_sil", v)
    var djFxVolume: Float get() = sp.getFloat("dj_fx", 0.6f); set(v) = sp.edit().putFloat("dj_fx", v).apply()
    var eqOn: Boolean get() = b("eq_on", true); set(v) = setB("eq_on", v)
    var eqPreset: String get() = sp.getString("eq_preset", "Normal") ?: "Normal"; set(v) = sp.edit().putString("eq_preset", v).apply()
    fun eqBands(preset: String): FloatArray? = sp.getString("eq_$preset", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
    fun setEqBands(preset: String, v: FloatArray) = sp.edit().putString("eq_$preset", v.joinToString(",")).apply()
    var updateSnoozeUntil: Long get() = sp.getLong("upd_snooze", 0); set(v) = sp.edit().putLong("upd_snooze", v).apply()
    var overlayAsked: Boolean get() = b("overlay_asked", false); set(v) = setB("overlay_asked", v)
    var djBatteryAsked: Boolean get() = b("dj_batt", false); set(v) = setB("dj_batt", v)
    /** Guardar las descargas también en una carpeta del celular. */
    var saveLocal: Boolean get() = b("save_local", false); set(v) = setB("save_local", v)
    var saveLocalPath: String get() = sp.getString("save_local_path", "Music/DownSong/") ?: "Music/DownSong/"; set(v) = sp.edit().putString("save_local_path", v).apply()
    var repeat: Int get() = sp.getInt("repeat", 0); set(v) = sp.edit().putInt("repeat", v).apply()
    var lastPlaylists: Set<Long>
        get() = sp.getStringSet("last_pl", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("last_pl", v.map { it.toString() }.toSet()).apply()

    // Última cola (para retomar al abrir la app)
    var qIds: List<Long>
        get() = sp.getString("q_ids", "")!!.split(',').mapNotNull { it.toLongOrNull() }
        set(v) = sp.edit().putString("q_ids", v.joinToString(",")).apply()
    var qIndex: Int get() = sp.getInt("q_idx", 0); set(v) = sp.edit().putInt("q_idx", v).apply()
    var qPos: Long get() = sp.getLong("q_pos", 0); set(v) = sp.edit().putLong("q_pos", v).apply()
    var qPlaylist: Long get() = sp.getLong("q_pl", Long.MIN_VALUE); set(v) = sp.edit().putLong("q_pl", v).apply()
    var qName: String get() = sp.getString("q_name", "") ?: ""; set(v) = sp.edit().putString("q_name", v).apply()
    var qVideo: Boolean get() = b("q_video", false); set(v) = setB("q_video", v)

    companion object {
        fun applyTheme(ctx: Context) {
            AppCompatDelegate.setDefaultNightMode(when (Prefs2(ctx).theme) {
                AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppTheme.DARK, AppTheme.DYNAMIC -> AppCompatDelegate.MODE_NIGHT_YES
            })
        }
    }
}
