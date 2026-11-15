package com.gabo.ytmp3

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale

/** Ajustes al estilo de la app Ajustes de iOS (listas agrupadas). */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
    private val regular by lazy { ResourcesCompat.getFont(this, R.font.inter_regular) }
    private val semibold by lazy { ResourcesCompat.getFont(this, R.font.inter_semibold) }
    private val bold by lazy { ResourcesCompat.getFont(this, R.font.inter_bold) }
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private val verify = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { build() }
    private val importCookies = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val n = Cookies.import(this, uri)
            toast("Importadas $n cookies ✓")
        } catch (e: Exception) { toast(e.message ?: "Archivo no válido") }
        build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.ios_bg))
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(32f))
        }
        scroll.addView(content)
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) build()   // p. ej. al volver de los ajustes de batería
    }

    // ------------------------------------------------------------------ construcción
    private fun build() {
        content.removeAllViews()

        // Barra superior: "‹ Atrás" + título grande
        content.addView(TextView(this).apply {
            text = "‹ Atrás"
            setTextColor(color(R.color.ios_blue)); textSize = 17f; typeface = regular
            setPadding(0, dp(14f), dp(12f), dp(4f))
            setOnClickListener { it.haptic(); finish() }
        })
        content.addView(TextView(this).apply {
            text = "Ajustes"; typeface = bold; textSize = 34f; letterSpacing = -0.02f
            setTextColor(color(R.color.ios_label))
            setPadding(0, 0, 0, dp(4f))
        })

        buildSupport()
        buildSpeed()
        buildBackground()
        buildVerification()
        buildStats()
        buildYtdlp()
        buildAbout()
    }

    private fun buildSupport() {
        header("Apoya MegaDown")
        val card = card()
        card.addView(row("🍔", "Tengo hambre", "Dona", valueColor = R.color.ios_orange,
            position = Pos.SINGLE) { Donate.show(this) })
        content.addView(card)
        footer("MegaDown es gratis y sin anuncios. Puedes apoyarla con Yape, Plin o una transferencia al BCP.")
    }

    private fun buildSpeed() {
        header("Velocidad de descarga")
        val card = card()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(12f))
        }
        val desc = TextView(this).apply {
            textSize = 13f; setTextColor(color(R.color.ios_secondary)); setPadding(dp(2f), dp(10f), 0, 0)
        }
        fun refreshDesc() {
            val n = Device.limitFor(this, prefs.speedMode)
            desc.text = when (prefs.speedMode) {
                SpeedMode.NORMAL -> "2 descargas a la vez. Ahorra batería y datos."
                SpeedMode.FAST -> "$n descargas a la vez. Equilibrio entre velocidad y batería."
                SpeedMode.ULTRA -> "⚡ Hasta $n descargas a la vez, el máximo que aguanta este celular. " +
                    "Se ajusta solo según temperatura, batería y red."
            }
        }
        box.addView(segmented(listOf("Normal", "Rápido", "⚡ Ultra"), prefs.speedMode.ordinal) { i ->
            prefs.speedMode = SpeedMode.entries[i]
            refreshDesc()
        })
        refreshDesc()
        box.addView(desc)
        card.addView(box)
        card.addView(separator(0))
        card.addView(switchRow("Ultra solo con Wi-Fi", "Con datos móviles se usa el modo Rápido", prefs.ultraWifiOnly) {
            prefs.ultraWifiOnly = it
        })
        card.addView(separator(14))
        card.addView(switchRow("Protección de calor", "Desactiva Ultra y baja el ritmo si el celular se calienta", prefs.heatProtect) {
            prefs.heatProtect = it
        })
        content.addView(card)

        val bat = Device.battery(this)
        val mi = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        footer(String.format(Locale.US, "Tu celular: %d núcleos · %.1f GB de RAM%s · Ultra = %d a la vez",
            Runtime.getRuntime().availableProcessors(), mi.totalMem / 1e9,
            bat.tempC?.let { " · %.0f °C".format(Locale.US, it) } ?: "",
            Device.maxParallel(this)))
    }

    private fun buildBackground() {
        header("Segundo plano")
        val card = card()
        val ok = Device.ignoresBatteryOptimizations(this)
        card.addView(row("🔒", "Descargar con pantalla bloqueada",
            if (ok) "Activado ✓" else "Activar", valueColor = if (ok) R.color.ios_green else R.color.ios_blue,
            position = Pos.TOP) {
            if (!ok) requestBatteryExemption(this)
        })
        card.addView(separator(52))
        card.addView(row("🛠️", "Ayuda para Xiaomi, Samsung, Huawei…", "", position = Pos.BOTTOM) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/")))
        })
        content.addView(card)
        footer("Algunas marcas detienen las apps con la pantalla apagada aunque tengan permiso. " +
            "Si pasa, abre la ayuda y sigue los pasos para tu marca.")
    }

    private fun buildVerification() {
        header("Cuentas y verificación")
        val card = card()
        val yt = Cookies.has(this, Platform.YOUTUBE)
        val ig = Cookies.has(this, Platform.INSTAGRAM)
        val tt = Cookies.has(this, Platform.TIKTOK)
        val fb = Cookies.has(this, Platform.FACEBOOK)
        card.addView(row("▶️", "YouTube: verificar", if (yt) "Guardada ✓" else "",
            valueColor = R.color.ios_green, position = Pos.TOP) {
            verify.launch(VerifyActivity.intent(this, Platform.YOUTUBE))
        })
        card.addView(separator(52))
        card.addView(row("📸", "Instagram: iniciar sesión (opcional)", if (ig) "Conectado ✓" else "",
            valueColor = R.color.ios_green, position = Pos.MID) {
            verify.launch(VerifyActivity.intent(this, Platform.INSTAGRAM))
        })
        card.addView(separator(52))
        card.addView(row("🎵", "TikTok: verificar (opcional)", if (tt) "Guardada ✓" else "",
            valueColor = R.color.ios_green, position = Pos.MID) {
            verify.launch(VerifyActivity.intent(this, Platform.TIKTOK))
        })
        card.addView(separator(52))
        card.addView(row("📘", "Facebook: iniciar sesión (opcional)", if (fb) "Conectado ✓" else "",
            valueColor = R.color.ios_green, position = Pos.MID) {
            verify.launch(VerifyActivity.intent(this, Platform.FACEBOOK))
        })
        card.addView(separator(52))
        card.addView(row("📄", "Importar cookies.txt", "", position = if (Cookies.has(this)) Pos.MID else Pos.BOTTOM) {
            importCookies.launch(arrayOf("text/plain", "*/*"))
        })
        if (Cookies.has(this)) {
            card.addView(separator(52))
            card.addView(row("🗑️", "Cerrar sesiones y borrar datos", "", labelColor = R.color.ios_red, position = Pos.BOTTOM) {
                Cookies.clear(this); toast("Sesiones y verificaciones borradas"); build()
            })
        }
        content.addView(card)
        footer("No hace falta iniciar sesión para descargar. Solo si una plataforma lo exige (o pide «confirma que no eres un robot») " +
            "la app pausa y te avisa. Los datos solo se guardan en tu teléfono; para Instagram y Facebook te recomendamos una cuenta secundaria.")
    }

    private fun buildStats() {
        header("Tus estadísticas")
        val card = card()
        val tiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8f), dp(14f), dp(8f), dp(6f))
        }
        tiles.addView(tile("🎵", prefs.statSongs.toString(), "descargas", R.color.ios_red))
        tiles.addView(tile("💾", Util.fmtBytes(prefs.statBytes), "guardados", R.color.ios_blue))
        tiles.addView(tile("⚡", Util.fmtHours(prefs.statSavedSec), "ahorrado", R.color.ios_orange))
        card.addView(tiles)
        card.addView(TextView(this).apply {
            text = "Últimos 7 días"; typeface = semibold; textSize = 13f
            setTextColor(color(R.color.ios_secondary)); setPadding(dp(16f), dp(8f), 0, 0)
        })
        card.addView(BarChartView(this).apply {
            data = prefs.last7Days()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130f)).apply {
                setMargins(dp(8f), dp(4f), dp(8f), dp(12f))
            }
        })
        content.addView(card)
        footer("«Ahorrado» es el tiempo que te ahorraron las descargas simultáneas frente a bajarlas una por una.")
    }

    private fun buildYtdlp() {
        header("Actualizaciones")
        val appCard = card()
        appCard.addView(row("📲", "Actualizar MegaDown", "v" + AppUpdater.currentVersion(this),
            position = Pos.SINGLE) { AppUpdater.check(this, manual = true) })
        content.addView(appCard)
        footer("La app revisa GitHub sola al abrirse y te avisa si hay una versión nueva.")

        header("Motor de descarga")
        val card = card()
        card.addView(row("⚙️", "yt-dlp", Updater.currentVersion(this), position = Pos.TOP, chevron = false) {})
        card.addView(separator(52))
        card.addView(row("🔄", "Buscar actualización", "", position = Pos.BOTTOM) {
            if (DownloadState.running) { toast("Espera a que termine la descarga"); return@row }
            toast("Buscando actualización…")
            lifecycleScope.launch {
                val msg = try {
                    App.ready.await()
                    val v = Updater.updateNow(applicationContext)
                    if (v == null) "yt-dlp ya está al día (${Updater.currentVersion(applicationContext)})"
                    else "yt-dlp actualizado a $v"
                } catch (e: Throwable) { "No se pudo actualizar: ${e.message}" }
                toast(msg); build()
            }
        })
        content.addView(card)
    }

    private fun buildAbout() {
        header("Acerca de")
        val card = card()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16f), dp(22f), dp(16f), dp(20f))
        }
        box.addView(FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.ic_launcher_bg_round)
            elevation = dp(4f).toFloat()
            addView(ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_launcher_fg)
                layoutParams = FrameLayout.LayoutParams(dp(84f), dp(84f))
            })
            layoutParams = LinearLayout.LayoutParams(dp(84f), dp(84f))
        })
        box.addView(TextView(this).apply {
            text = "MegaDown"; typeface = bold; textSize = 22f; setTextColor(color(R.color.ios_label))
            setPadding(0, dp(12f), 0, 0)
        })
        val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        box.addView(TextView(this).apply {
            text = "Versión $v"; textSize = 14f; setTextColor(color(R.color.ios_secondary))
        })
        box.addView(TextView(this).apply {
            text = "Desarrollado por"; textSize = 13f; setTextColor(color(R.color.ios_secondary))
            setPadding(0, dp(18f), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = "Gabo Matica"; typeface = semibold; textSize = 18f; setTextColor(color(R.color.ios_label))
        })
        box.addView(TextView(this).apply {
            text = "@GabLuciani"; typeface = semibold; textSize = 16f; setTextColor(color(R.color.ios_blue))
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            setOnClickListener { copyHandle(this@SettingsActivity, it) }
            pressable()
        })
        for (i in 0 until box.childCount) (box.getChildAt(i) as? TextView)?.gravity = Gravity.CENTER
        card.addView(box)
        content.addView(card)
        footer("Usa yt-dlp y FFmpeg. Fuente Inter © Rasmus Andersson (SIL OFL). " +
            "Descarga solo contenido que tengas derecho a guardar.")
    }

    // ------------------------------------------------------------------ piezas
    private enum class Pos { TOP, MID, BOTTOM, SINGLE }

    private fun header(t: String) = content.addView(TextView(this).apply {
        text = t.uppercase(Locale.getDefault()); textSize = 13f; letterSpacing = 0.02f
        setTextColor(color(R.color.ios_secondary))
        setPadding(dp(16f), dp(26f), dp(16f), dp(7f))
    })

    private fun footer(t: String) = content.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(color(R.color.ios_secondary))
        setPadding(dp(16f), dp(7f), dp(16f), 0)
    })

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        clipToOutline = true
    }

    private fun separator(startDp: Int) = View(this).apply {
        setBackgroundColor(color(R.color.ios_separator))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.5f)).apply {
            marginStart = dp(startDp.toFloat())
        }
    }

    private fun row(
        icon: String, label: String, value: String,
        valueColor: Int = R.color.ios_secondary, labelColor: Int = R.color.ios_label,
        position: Pos = Pos.SINGLE, chevron: Boolean = true, onClick: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52f)
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        setBackgroundResource(when (position) {
            Pos.TOP -> R.drawable.bg_row_top; Pos.MID -> R.drawable.bg_row_mid
            Pos.BOTTOM -> R.drawable.bg_row_bottom; Pos.SINGLE -> R.drawable.bg_row_single
        })
        addView(TextView(this@SettingsActivity).apply {
            text = icon; textSize = 19f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28f), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(TextView(this@SettingsActivity).apply {
            text = label; textSize = 16f; typeface = regular; setTextColor(color(labelColor))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10f) }
        })
        if (value.isNotEmpty()) addView(TextView(this@SettingsActivity).apply {
            text = value; textSize = 15f; setTextColor(color(valueColor))
        })
        if (chevron) addView(ImageView(this@SettingsActivity).apply {
            setImageResource(R.drawable.ic_chevron)
            layoutParams = LinearLayout.LayoutParams(dp(14f), dp(14f)).apply { marginStart = dp(8f) }
        })
        setOnClickListener { it.haptic(); onClick() }
    }

    private fun switchRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            val texts = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this@SettingsActivity).apply {
                text = label; textSize = 16f; setTextColor(color(R.color.ios_label))
            })
            texts.addView(TextView(this@SettingsActivity).apply {
                text = sub; textSize = 12f; setTextColor(color(R.color.ios_secondary))
            })
            addView(texts)
            val sw = SwitchCompat(this@SettingsActivity).apply {
                setThumbResource(R.drawable.ios_switch_thumb)
                setTrackResource(R.drawable.ios_switch_track)
                thumbTintList = null; trackTintList = null
                showText = false
                isChecked = checked
                setOnCheckedChangeListener { v, c -> v.haptic(); onChange(c) }
            }
            addView(sw)
            setOnClickListener { sw.toggle() }
        }

    private fun segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_segment)
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f))
        }
        val views = labels.mapIndexed { i, l ->
            TextView(this).apply {
                text = l; typeface = semibold; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColorStateList(this@SettingsActivity, R.color.segment_text))
                setBackgroundResource(R.drawable.bg_segment_item)
                isSelected = i == selected
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
        }
        views.forEachIndexed { i, v ->
            v.setOnClickListener {
                it.haptic()
                views.forEach { o -> o.isSelected = o === v }
                if (i == 2) v.bounce()
                onPick(i)
            }
            seg.addView(v)
        }
        return seg
    }

    private fun tile(emoji: String, value: String, label: String, colorRes: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@SettingsActivity).apply { text = emoji; textSize = 22f; gravity = Gravity.CENTER })
        addView(TextView(this@SettingsActivity).apply {
            text = value; typeface = bold; textSize = 20f; setTextColor(color(colorRes)); gravity = Gravity.CENTER
        })
        addView(TextView(this@SettingsActivity).apply {
            text = label; textSize = 12f; setTextColor(color(R.color.ios_secondary)); gravity = Gravity.CENTER
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        /** Pide a Android que no limite la app por batería (descargas con pantalla bloqueada). */
        @SuppressLint("BatteryLife")
        fun requestBatteryExemption(a: AppCompatActivity) {
            try {
                a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${a.packageName}")))
            } catch (e: Exception) {
                a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        fun copyHandle(a: AppCompatActivity, v: View) {
            v.haptic()
            a.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("usuario", "@GabLuciani"))
            Toast.makeText(a, "@GabLuciani copiado", Toast.LENGTH_SHORT).show()
        }

        @Suppress("unused")
        private fun confirm(a: AppCompatActivity, title: String, msg: String, ok: () -> Unit) =
            MaterialAlertDialogBuilder(a).setTitle(title).setMessage(msg)
                .setPositiveButton("Aceptar") { _, _ -> ok() }.setNegativeButton("Cancelar", null).show()
    }
}
