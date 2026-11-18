package com.gabo.ytmp3

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class AppTheme(val label: String) { LIGHT("Claro"), DARK("Oscuro"), SYSTEM("Sistema"), DYNAMIC("Dinámico") }

/** Ajustes de DownSong 2 (reproductor, apariencia, perfil). */
class Prefs2(ctx: Context) {
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
