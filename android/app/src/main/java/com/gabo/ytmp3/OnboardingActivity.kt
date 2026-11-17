package com.gabo.ytmp3

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

/** Bienvenida (solo la primera vez): crear perfil, permisos e importar música. */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var stage: FrameLayout
    private lateinit var prefs: Prefs2
    private var photo: File? = null
    private var avatarView: ImageView? = null
    private var permStep = 0

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
                val s = 512f / maxOf(raw.width, raw.height)
                val b = if (s < 1f) Bitmap.createScaledBitmap(raw, (raw.width * s).toInt(), (raw.height * s).toInt(), true) else raw
                File(filesDir, "profile.jpg").also { out -> out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            }
            if (f != null) { photo = f; prefs.photoPath = f.absolutePath; refreshAvatar("") }
        }
    }

    private val askPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { nextPerm() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs2(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(NotesView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        welcome()
    }

    private fun show(v: View) {
        stage.removeAllViews()
        v.alpha = 0f; v.translationY = dp(24).toFloat()
        stage.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.animate().alpha(1f).translationY(0f).setDuration(380).start()
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(32), dp(56), dp(32), dp(40))
    }

    private fun bigButton(label: String, onClick: () -> Unit) = text(label, 17f, Color.WHITE, 2).apply {
        gravity = Gravity.CENTER; minHeight = dp(54)
        background = roundRect(BRAND_RED, dp(27).toFloat())
        pressable(); setOnClickListener { it.haptic(true); onClick() }
    }

    private fun textButton(label: String, onClick: () -> Unit) = text(label, 15f, 0xB3FFFFFF.toInt(), 1).apply {
        gravity = Gravity.CENTER; minHeight = dp(48)
        setOnClickListener { it.haptic(); onClick() }
    }

    // ------------------------------------------------------------------ 1. bienvenida
    private fun welcome() {
        val col = column()
        col.addView(View(this), lp(1, 0, 1f))
        col.addView(ImageView(this).apply { setImageResource(R.drawable.logo_downsong) }, lp(dp(110), dp(110)))
        col.addView(text("DownSong", 38f, Color.WHITE, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        col.addView(text("Tu música. Sin anuncios.\nSin internet. Libre.", 18f, 0xCCFFFFFF.toInt(), 0, 0).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
        })
        col.addView(View(this), lp(1, 0, 1f))
        col.addView(bigButton("Empezar") { profile() }, lp(MATCH, WRAP))
        show(col)
    }

    // ------------------------------------------------------------------ 2. perfil
    private fun profile() {
        val col = column()
        col.addView(text("Crea tu perfil", 28f, Color.WHITE, 2).apply { gravity = Gravity.CENTER })
        col.addView(text("Así te verás en DownSong", 15f, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(28)) })
        val avFrame = FrameLayout(this)
        val av = ImageView(this)
        avatarView = av
        avFrame.addView(av, FrameLayout.LayoutParams(dp(132), dp(132)))
        avFrame.addView(icon(R.drawable.ic_camera, 38, Color.WHITE, 8).apply {
            background = oval(BRAND_RED)
        }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.BOTTOM or Gravity.END))
        avFrame.setOnClickListener {
            it.haptic(); pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        col.addView(avFrame, lp(dp(132), dp(132)))
        col.addView(text("Foto opcional · toca para elegir", 13f, 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(26)) })

        val input = EditText(this).apply {
            hint = "Tu apodo"; setHintTextColor(0x80FFFFFF.toInt()); setTextColor(Color.WHITE)
            textSize = 20f; isSingleLine = true; gravity = Gravity.CENTER
            typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
            filters = arrayOf(LimitChars(20))
            background = roundRect(0x26FFFFFF, dp(14).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setText(prefs.nickname)
        }
        col.addView(input, lp(MATCH, WRAP))
        val counter = text("0/20", 12f, 0x80FFFFFF.toInt()).apply { gravity = Gravity.END; setPadding(0, dp(6), dp(4), 0) }
        col.addView(counter, lp(MATCH, WRAP))
        col.addView(View(this), lp(1, 0, 1f))
        val go = bigButton("Continuar") {
            prefs.nickname = input.text.toString().trim()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
            permStep = 0; nextPerm()
        }
        col.addView(go, lp(MATCH, WRAP))
        fun update() {
            val t = input.text.toString().trim()
            counter.text = "${t.codePointCount(0, t.length)}/20"
            go.isEnabled = t.isNotEmpty(); go.alpha = if (t.isNotEmpty()) 1f else 0.35f
            refreshAvatar(t)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = update()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        update()
        show(col)
    }

    private var lastAvatarNick: String? = null
    private fun refreshAvatar(nick: String) {
        val av = avatarView ?: return
        val p = photo ?: prefs.photoPath?.let(::File)?.takeIf { it.exists() }
        if (p != null) { av.setImageBitmap(Covers.avatarBitmap(this)); return }
        val key = nick.ifEmpty { "DownSong" }
        if (key == lastAvatarNick) return
        lastAvatarNick = key
        av.setImageBitmap(KaleidoRenderer.avatar(key, 264))
    }

    // ------------------------------------------------------------------ 3. permisos, uno por uno
    private data class Perm(val emoji: String, val title: String, val text: String, val needed: () -> Boolean, val ask: () -> Unit)

    private val perms by lazy {
        listOf(
            Perm("🔔", "Notificaciones", "Para ver tus descargas y controlar la música desde la pantalla de bloqueo.",
                { Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED },
                { askPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }),
            Perm("🎵", "Tu música del celular", "Para agregar a DownSong las canciones que ya tienes, sin copiarlas ni ocupar más espacio.",
                { !ImportSheet.granted(this) }, { askPerm.launch(ImportSheet.permission) }),
            Perm("🔋", "Sin cortes en segundo plano", "Para que la música y las descargas sigan con la pantalla bloqueada.",
                { !Device.ignoresBatteryOptimizations(this) }, { SettingsActivity.requestBatteryExemption(this); pendingBattery = true }),
        )
    }
    private var pendingBattery = false

    override fun onResume() {
        super.onResume()
        if (pendingBattery) { pendingBattery = false; nextPerm() }
    }

    private fun nextPerm() {
        while (permStep < perms.size && !perms[permStep].needed()) permStep++
        if (permStep >= perms.size) { importStep(); return }
        val p = perms[permStep]
        permStep++
        val col = column()
        col.addView(View(this), lp(1, 0, 1f))
        col.addView(text(p.emoji, 64f, Color.WHITE).apply { gravity = Gravity.CENTER })
        col.addView(text(p.title, 26f, Color.WHITE, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(10)) })
        col.addView(text(p.text, 16f, 0xCCFFFFFF.toInt(), 0, 0).apply { gravity = Gravity.CENTER })
        col.addView(View(this), lp(1, 0, 1f))
        col.addView(bigButton("Permitir") { p.ask() }, lp(MATCH, WRAP))
        col.addView(textButton("Ahora no") { nextPerm() }, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        show(col)
    }

    // ------------------------------------------------------------------ 4. importar
    private fun importStep() {
        if (!ImportSheet.granted(this)) { finishOnboarding(); return }
        val col = column()
        col.addView(View(this), lp(1, 0, 1f))
        col.addView(text("📥", 64f, Color.WHITE).apply { gravity = Gravity.CENTER })
        val title = text("Buscando tu música…", 24f, Color.WHITE, 2, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(10)) }
        col.addView(title)
        val sub = text("", 16f, 0xCCFFFFFF.toInt(), 0, 0).apply { gravity = Gravity.CENTER }
        col.addView(sub)
        val spin = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(BRAND_RED) }
        col.addView(spin, lp(dp(40), dp(40)).apply { topMargin = dp(16) })
        col.addView(View(this), lp(1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actions, lp(MATCH, WRAP))
        show(col)
        lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) { Library.scanDevice(this@OnboardingActivity) }
            spin.visibility = View.GONE
            if (songs.isEmpty()) { finishOnboarding(); return@launch }
            title.text = "¿Importamos la música que ya tienes?"
            sub.text = "Encontramos ${songs.size} ${if (songs.size == 1) "canción" else "canciones"} en tu celular. " +
                "Se agregan a la playlist «Mi música» sin copiarlas."
            actions.addView(bigButton("Importar ${songs.size}") {
                actions.removeAllViews(); spin.visibility = View.VISIBLE; title.text = "Importando…"; sub.text = ""
                lifecycleScope.launch {
                    val ids = withContext(Dispatchers.IO) { Library.importLocal(this@OnboardingActivity, songs) }
                    val pl = Library.createPlaylist(this@OnboardingActivity, "Mi música")
                    Library.addToPlaylists(this@OnboardingActivity, ids, listOf(pl))
                    finishOnboarding()
                }
            }, lp(MATCH, WRAP))
            actions.addView(textButton("Ahora no") { finishOnboarding() }, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        }
    }

    private fun finishOnboarding() {
        prefs.onboarded = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

/** Limita por caracteres visibles (un emoji cuenta como uno). */
class LimitChars(private val max: Int) : InputFilter {
    override fun filter(source: CharSequence, start: Int, end: Int, dest: android.text.Spanned, dstart: Int, dend: Int): CharSequence? {
        val keep = dest.toString().removeRange(dstart, dend)
        val cur = keep.codePointCount(0, keep.length)
        val add = source.subSequence(start, end).toString()
        val addCount = add.codePointCount(0, add.length)
        if (cur + addCount <= max) return null
        val room = (max - cur).coerceAtLeast(0)
        return if (room == 0) "" else add.substring(0, add.offsetByCodePoints(0, room))
    }
}

/** Fondo negro con notas musicales blancas flotando (solo en la bienvenida). */
class NotesView(ctx: Context) : View(ctx) {
    private val glyphs = arrayOf("♪", "♫", "♬", "♩")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = ResourcesCompat.getFont(ctx, R.font.inter_bold) }
    private data class N(val x: Float, val speed: Float, val size: Float, val g: String, val phase: Float, val alpha: Int)
    private val notes = List(16) {
        val r = Random(it * 31 + 5)
        N(r.nextFloat(), 0.015f + r.nextFloat() * 0.03f, 18f + r.nextFloat() * 26f, glyphs[it % 4], r.nextFloat() * 6.28f, 40 + r.nextInt(90))
    }
    private val t0 = SystemClock.uptimeMillis()

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        val d = resources.displayMetrics.density
        for (n in notes) {
            val y = (1.1f - ((t * n.speed + n.phase) % 1.2f)) * height
            val x = n.x * width + sin(t * 0.6f + n.phase) * 18f * d
            paint.textSize = n.size * d
            paint.alpha = n.alpha
            c.drawText(n.g, x, y, paint)
        }
        if (isShown) postInvalidateDelayed(40)
    }
}

@Suppress("unused") private fun TextView.noop() = Unit
