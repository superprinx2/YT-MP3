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
    private val section: String? get() = intent.getStringExtra(EXTRA_SECTION)
    private val prefs2 by lazy { Prefs2(this) }

    private val sections = listOf(
        Triple("playback", "🎧", "Reproducción"),
        Triple("appearance", "🎨", "Apariencia"),
        Triple("lock", "🔒", "Pantalla de bloqueo"),
        Triple("downloads", "⬇️", "Descargas"),
        Triple("storage", "💾", "Biblioteca y almacenamiento"),
        Triple("accounts", "👤", "Cuentas"),
        Triple("updates", "📲", "Actualizaciones"),
    )

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val raw = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    ?: return@withContext null
                val s = 512f / maxOf(raw.width, raw.height)
                val b = if (s < 1f) android.graphics.Bitmap.createScaledBitmap(raw, (raw.width * s).toInt(), (raw.height * s).toInt(), true) else raw
                java.io.File(filesDir, "profile.jpg").also { out -> out.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) } }
            }
            if (f != null) { prefs2.photoPath = f.absolutePath; build() }
        }
    }

    private fun open(s: String) = startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_SECTION, s))

    private fun build() {
        content.removeAllViews()
        val sec = section
        val label = if (sec == null) "Ajustes" else when (sec) {
            "profile" -> "Perfil"; "support" -> "Apoya DownSong"; "about" -> "Acerca de"; "eq" -> "Ecualizador"
            else -> sections.firstOrNull { it.first == sec }?.third ?: "Ajustes"
        }
        // Barra superior: "‹ Atrás" + título grande
        content.addView(TextView(this).apply {
            text = if (sec == null) "‹ Atrás" else "‹ Ajustes"
            setTextColor(color(R.color.ios_blue)); textSize = 17f; typeface = regular
            setPadding(0, dp(14f), dp(12f), dp(4f))
            setOnClickListener { it.haptic(); finish() }
        })
        content.addView(TextView(this).apply {
            text = label; typeface = bold; textSize = 34f; letterSpacing = -0.02f
            setTextColor(color(R.color.ios_label))
            setPadding(0, 0, 0, dp(4f))
        })

        when (sec) {
            null -> buildRoot()
            "playback" -> buildPlayback()
            "eq" -> buildEq()
            "appearance" -> buildAppearance()
            "lock" -> buildLock()
            "downloads" -> { buildSpeed(); buildBackground(); buildStats() }
            "storage" -> buildStorage()
            "accounts" -> buildVerification()
            "profile" -> buildProfile()
            "updates" -> buildYtdlp()
            "support" -> buildSupport()
            "about" -> buildAbout()
        }
    }

    private fun buildRoot() {
        // tarjeta de perfil
        val pc = card()
        pc.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            setBackgroundResource(R.drawable.bg_row_single)
            addView(ImageView(this@SettingsActivity).apply { setImageBitmap(Covers.avatarBitmap(this@SettingsActivity)) },
                LinearLayout.LayoutParams(dp(58f), dp(58f)))
            val t = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, 0, 0) }
            t.addView(TextView(this@SettingsActivity).apply {
                text = prefs2.nickname.ifEmpty { "Tu perfil" }; typeface = semibold; textSize = 20f; setTextColor(color(R.color.ios_label))
            })
            t.addView(TextView(this@SettingsActivity).apply { text = "Apodo y foto"; textSize = 14f; setTextColor(color(R.color.ios_secondary)) })
            addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(this@SettingsActivity).apply { setImageResource(R.drawable.ic_chevron) }, LinearLayout.LayoutParams(dp(14f), dp(14f)))
            setOnClickListener { it.haptic(); open("profile") }
        })
        content.addView(pc)
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(26f)))
        val card = card()
        sections.forEachIndexed { i, (key, emoji, name) ->
            if (i > 0) card.addView(separator(52))
            card.addView(row(emoji, name, "", position = when (i) { 0 -> Pos.TOP; sections.size - 1 -> Pos.BOTTOM; else -> Pos.MID }) { open(key) })
        }
        content.addView(card)
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(26f)))
        val c2 = card()
        c2.addView(row("ℹ️", "Acerca de", "v" + AppUpdater.currentVersion(this), position = Pos.SINGLE) { open("about") })
        content.addView(c2)
    }

    private fun buildPlayback() {
        header("Transición entre canciones")
        val card = card()
        val valueText = TextView(this).apply { textSize = 15f; setTextColor(color(R.color.ios_secondary)) }
        val slider = android.widget.SeekBar(this).apply {
            max = 11; progress = prefs2.crossfadeSec - 1
            isEnabled = prefs2.crossfadeOn
            setPadding(dp(18f), dp(6f), dp(18f), dp(14f))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    prefs2.crossfadeSec = p + 1; valueText.text = "${p + 1} s"
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar) { s.haptic() }
            })
        }
        valueText.text = "${prefs2.crossfadeSec} s"
        card.addView(switchRow("Crossfade", "Funde el final de una canción con el inicio de la siguiente", prefs2.crossfadeOn) {
            prefs2.crossfadeOn = it; slider.isEnabled = it; slider.alpha = if (it) 1f else 0.4f
        })
        card.addView(separator(14))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), 0)
            addView(TextView(this@SettingsActivity).apply { text = "Duración del fundido"; textSize = 16f; setTextColor(color(R.color.ios_label)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueText)
        })
        slider.alpha = if (prefs2.crossfadeOn) 1f else 0.4f
        card.addView(slider)
        content.addView(card)
        footer("De 1 a 12 segundos. Así no sientes la pausa entre canción y canción.")

        header("Sonido")
        val ce = card()
        ce.addView(row("🎚️", "Ecualizador", if (prefs2.eqOn) prefs2.eqPreset else "Apagado", position = Pos.SINGLE) { open("eq") })
        content.addView(ce)
        val cs = card()
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(12f)))
        cs.addView(switchRow("Saltar silencios", "Omite los silencios largos al inicio y al final de cada canción", prefs2.skipSilence) {
            prefs2.skipSilence = it
        })
        content.addView(cs)

        header("Modo DJ")
        val cd = card()
        val fxVal = TextView(this).apply { textSize = 15f; setTextColor(color(R.color.ios_secondary)); text = "${(prefs2.djFxVolume * 100).toInt()} %" }
        cd.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14f), dp(12f), dp(14f), 0)
            addView(TextView(this@SettingsActivity).apply { text = "Volumen de los efectos DJ"; textSize = 16f; setTextColor(color(R.color.ios_label)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(fxVal)
        })
        cd.addView(android.widget.SeekBar(this).apply {
            max = 100; progress = (prefs2.djFxVolume * 100).toInt(); setPadding(dp(18f), dp(8f), dp(18f), dp(14f))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar, p: Int, u: Boolean) { prefs2.djFxVolume = p / 100f; fxVal.text = "$p %" }
                override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar) { s.haptic(); DjFx.play(this@SettingsActivity, prefs2.djFxVolume) }
            })
        })
        content.addView(cd)
        footer("Suena un efecto de mezcla (barrido, scratch, bocina…) cada vez que el DJ cambia de canción.")

        header("Volumen")
        val c2 = card()
        c2.addView(switchRow("Nivelar volumen", "Todas las canciones suenan con el mismo volumen", prefs2.normalize) {
            prefs2.normalize = it
        })
        content.addView(c2)
        footer("Evita que una canción o video suene mucho más fuerte o más bajo que el resto.")
    }

    private fun buildAppearance() {
        header("Tema")
        val card = card()
        AppTheme.entries.forEachIndexed { i, t ->
            if (i > 0) card.addView(separator(14))
            val sub = when (t) {
                AppTheme.LIGHT -> "Fondo claro"; AppTheme.DARK -> "Fondo oscuro"
                AppTheme.SYSTEM -> "Sigue el modo claro u oscuro del celular"
                AppTheme.DYNAMIC -> "Los colores cambian con la portada de la canción que suena"
            }
            card.addView(checkRow(t.label, sub, prefs2.theme == t,
                when (i) { 0 -> Pos.TOP; AppTheme.entries.size - 1 -> Pos.BOTTOM; else -> Pos.MID }) {
                prefs2.theme = t; Prefs2.applyTheme(this); build()
            })
        }
        content.addView(card)

        header("Modo de uso")
        val cu = card()
        UsageMode.entries.forEachIndexed { i, m ->
            if (i > 0) cu.addView(separator(14))
            val sub = when (m) {
                UsageMode.MAX -> "Visualizador a 60 cuadros, efectos al máximo"
                UsageMode.BALANCED -> "El equilibrio ideal entre fluidez y batería"
                UsageMode.SAVER -> "Para celulares básicos o ahorrar batería: menos animaciones y efectos"
            }
            cu.addView(checkRow(m.label, sub, prefs2.usage == m, when (i) { 0 -> Pos.TOP; 2 -> Pos.BOTTOM; else -> Pos.MID }) {
                prefs2.usage = m; toast("Modo ${m.label} activado. Se aplica por completo al volver a abrir la app."); build()
            })
        }
        content.addView(cu)
        footer("La música, el crossfade, el ecualizador y el análisis del DJ funcionan igual en todos los modos.")

        header("Portadas")
        val c2 = card()
        c2.addView(switchRow("Canciones sin portada: efecto caleidoscopio", "Figuras de colores que se mueven con la música", prefs2.kaleidoNoCover) {
            prefs2.kaleidoNoCover = it
        })
        c2.addView(separator(14))
        c2.addView(switchRow("Caleidoscopio en todas mis canciones", "Usa el efecto incluso en las que tienen portada", prefs2.kaleidoAll) {
            prefs2.kaleidoAll = it
        })
        content.addView(c2)
        footer("El caleidoscopio cambia de forma y de color al ritmo de la canción.")
    }

    private fun buildLock() {
        header("Pantalla de bloqueo DownSong")
        val cl = card()
        cl.addView(switchRow("Pantalla de bloqueo propia", "Portada a pantalla completa y todos los controles con el celular bloqueado", prefs2.lockScreen) {
            prefs2.lockScreen = it
            if (it && !Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        cl.addView(separator(14))
        val ok = Settings.canDrawOverlays(this)
        cl.addView(row("🪟", "Permiso «Mostrar sobre otras apps»", if (ok) "Activado ✓" else "Activar",
            valueColor = if (ok) R.color.ios_green else R.color.ios_blue, position = Pos.BOTTOM) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        content.addView(cl)
        footer("Funciona en todas las marcas (Huawei, Xiaomi, Samsung…). Desliza hacia arriba para ir a tu bloqueo normal: tu PIN o huella siguen igual. " +
            "En Huawei activa también «Inicio automático» para DownSong en Ajustes › Batería.")

        header("Pantalla de bloqueo")
        val card = card()
        card.addView(switchRow("Botones aleatorio y repetir", "Además de anterior, pausa y siguiente", prefs2.lockButtons) {
            prefs2.lockButtons = it; PlaybackService.instance?.refreshButtons()
        })
        card.addView(separator(14))
        card.addView(row("🔔", "Ajustes de notificaciones", "", position = Pos.BOTTOM) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        content.addView(card)
        footer("¿No ves la música en la pantalla de bloqueo? Toca «Ajustes de notificaciones» y activa " +
            "«Reproducción de música» y «Pantalla de bloqueo». En Xiaomi activa también «Mostrar en pantalla de bloqueo»; " +
            "en Samsung, Ajustes › Pantalla de bloqueo › Notificaciones › Mostrar contenido.")
        footer("Con la pantalla bloqueada o la app minimizada ves qué canción suena, con su portada, y puedes pasar " +
            "de canción. Algunos celulares (Samsung, Xiaomi…) muestran además la portada de fondo; el diseño exacto lo pone Android.")
    }

    private fun buildStorage() {
        header("Espacio usado")
        val card = card()
        content.addView(card)
        val per = card()
        lifecycleScope.launch {
            val dao = Library.dao(this@SettingsActivity)
            val audio = dao.totalAudio(); val video = dao.totalVideo()
            val sizes = dao.sizes()
            card.addView(row("🎵", "Canciones (audio)", Util.fmtBytes(audio), position = Pos.TOP, chevron = false) {})
            card.addView(separator(52))
            card.addView(row("🎬", "Videos", Util.fmtBytes(video), position = Pos.MID, chevron = false) {})
            card.addView(separator(52))
            card.addView(row("🗑️", "Borrar todos los videos", "", labelColor = R.color.ios_red, position = Pos.BOTTOM) {
                confirmDeleteVideos(null, "todos los videos", video)
            })
            if (sizes.isNotEmpty()) {
                header("Por playlist")
                sizes.forEachIndexed { i, s ->
                    if (i > 0) per.addView(separator(14))
                    per.addView(row("", "${s.name} · ${s.count}", Util.fmtBytes(s.audio + s.video),
                        position = when { sizes.size == 1 -> Pos.SINGLE; i == 0 -> Pos.TOP; i == sizes.size - 1 -> Pos.BOTTOM; else -> Pos.MID }) {
                        if (s.video > 0) confirmDeleteVideos(s.id, "los videos de «${s.name}»", s.video)
                        else toast("«${s.name}» no tiene videos guardados")
                    })
                }
                content.addView(per)
                footer("Toca una playlist para borrar sus videos y liberar espacio. El audio se conserva.")
            }
            header("Música del celular")
            val c3 = card()
            c3.addView(row("📥", "Agregar música del celular", "", position = Pos.SINGLE) { ImportSheet.start(this@SettingsActivity, null) })
            content.addView(c3)
            footer("La música del celular se lee en su lugar: no se copia ni ocupa espacio extra.")
        }
    }

    private fun confirmDeleteVideos(pid: Long?, what: String, bytes: Long) {
        ActionSheet.show(this, "¿Borrar $what? Liberas ${Util.fmtBytes(bytes)}. Las canciones se siguen escuchando (solo audio).",
            listOf("Borrar videos"), destructive = setOf(0)) {
            lifecycleScope.launch {
                val freed = Library.deleteVideos(this@SettingsActivity, pid)
                toast("Liberaste ${Util.fmtBytes(freed)} ✓"); build()
            }
        }
    }

    private fun buildProfile() {
        val card = card()
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(22f), 0, dp(18f))
            addView(ImageView(this@SettingsActivity).apply { setImageBitmap(Covers.avatarBitmap(this@SettingsActivity)) },
                LinearLayout.LayoutParams(dp(110f), dp(110f)))
            addView(TextView(this@SettingsActivity).apply {
                text = prefs2.nickname; typeface = bold; textSize = 22f; setTextColor(color(R.color.ios_label)); gravity = Gravity.CENTER
                setPadding(0, dp(12f), 0, 0)
            })
        })
        content.addView(card)
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(22f)))
        val c2 = card()
        c2.addView(row("✏️", "Apodo", prefs2.nickname, position = Pos.TOP) {
            PlaylistPicker.askName(this, "Tu apodo", prefs2.nickname, 20) { prefs2.nickname = it; build() }
        })
        c2.addView(separator(52))
        val hasPhoto = prefs2.photoPath != null
        c2.addView(row("📷", if (hasPhoto) "Cambiar foto" else "Elegir foto", "", position = if (hasPhoto) Pos.MID else Pos.BOTTOM) {
            pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        if (hasPhoto) {
            c2.addView(separator(52))
            c2.addView(row("🌀", "Usar avatar caleidoscopio", "", position = Pos.BOTTOM) { prefs2.photoPath = null; build() })
        }
        content.addView(c2)
        footer("Tu perfil solo vive en este celular.")
    }

    private fun buildEq() {
        val card = card()
        card.addView(switchRow("Ecualizador", "Ajusta el sonido a tu gusto", prefs2.eqOn) {
            prefs2.eqOn = it; PlaybackService.instance?.applyEq()
        })
        content.addView(card)
        header("Perfiles")
        val curve = EqCurveView(this).apply { bands = Eq.bands(prefs2) }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4f), 0, dp(8f)) }
        fun paintChips() {
            for (i in 0 until chips.childCount) {
                val v = chips.getChildAt(i) as TextView
                val on = v.text == prefs2.eqPreset
                v.background = roundRect(if (on) BRAND_RED else color(R.color.ios_card), dp(18f).toFloat())
                v.setTextColor(if (on) android.graphics.Color.WHITE else color(R.color.ios_label))
            }
        }
        Eq.presets.keys.forEach { name ->
            chips.addView(TextView(this).apply {
                text = name; textSize = 14f; typeface = semibold; gravity = Gravity.CENTER
                setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
                setOnClickListener {
                    it.haptic(); prefs2.eqPreset = name; prefs2.eqOn = true
                    curve.bands = Eq.bands(prefs2); paintChips(); PlaybackService.instance?.applyEq()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8f) })
        }
        paintChips()
        content.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val cc = card()
        curve.setPadding(dp(8f), 0, dp(8f), 0)
        curve.onChange = { b -> prefs2.setEqBands(prefs2.eqPreset, b); prefs2.eqOn = true; PlaybackService.instance?.applyEq() }
        cc.addView(curve, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230f)))
        content.addView(cc)
        footer("Arrastra los puntos hacia arriba o abajo para modificar el perfil. Cada perfil guarda tus cambios.")
        val cr = card()
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(12f)))
        cr.addView(row("↺", "Restablecer «${prefs2.eqPreset}»", "", position = Pos.SINGLE) {
            Eq.presets[prefs2.eqPreset]?.let { prefs2.setEqBands(prefs2.eqPreset, it) }
            curve.bands = Eq.bands(prefs2); PlaybackService.instance?.applyEq()
        })
        content.addView(cr)
    }

    private fun checkRow(label: String, sub: String, checked: Boolean, position: Pos, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            setBackgroundResource(when (position) {
                Pos.TOP -> R.drawable.bg_row_top; Pos.MID -> R.drawable.bg_row_mid
                Pos.BOTTOM -> R.drawable.bg_row_bottom; Pos.SINGLE -> R.drawable.bg_row_single
            })
            val texts = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@SettingsActivity).apply { text = label; textSize = 16f; setTextColor(color(R.color.ios_label)) })
            texts.addView(TextView(this@SettingsActivity).apply { text = sub; textSize = 12f; setTextColor(color(R.color.ios_secondary)) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (checked) addView(ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_check); setColorFilter(BRAND_RED)
            }, LinearLayout.LayoutParams(dp(22f), dp(22f)))
            setOnClickListener { it.haptic(); onClick() }
        }

    private fun buildSupport() {
        header("Apoya DownSong")
        val card = card()
        card.addView(row("🍔", "Tengo hambre", "Dona", valueColor = R.color.ios_orange,
            position = Pos.SINGLE) { Donate.show(this) })
        content.addView(card)
        footer("DownSong es gratis y sin anuncios. Puedes apoyarla con Yape, Plin o una transferencia al BCP.")
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
        appCard.addView(row("📲", "Actualizar DownSong", "v" + AppUpdater.currentVersion(this),
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
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_downsong)
            elevation = dp(4f).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(84f), dp(84f))
        })
        box.addView(TextView(this).apply {
            text = "DownSong"; typeface = bold; textSize = 22f; setTextColor(color(R.color.ios_label))
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
        const val EXTRA_SECTION = "section"

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
