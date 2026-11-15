package com.gabo.ytmp3

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Abre YouTube dentro de la app para que el usuario resuelva la verificación
 * ("confirma que no eres un robot") o inicie sesión si YouTube lo exige.
 * Al tocar "Listo" se guardan las cookies para que yt-dlp las use.
 */
class VerifyActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val platform by lazy {
        runCatching { Platform.valueOf(intent.getStringExtra(EXTRA_PLATFORM) ?: "") }.getOrDefault(Platform.YOUTUBE)
    }

    companion object {
        const val EXTRA_PLATFORM = "platform"

        fun intent(ctx: android.content.Context, p: Platform) =
            android.content.Intent(ctx, VerifyActivity::class.java).putExtra(EXTRA_PLATFORM, p.name)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
        val blue = ContextCompat.getColor(this, R.color.ios_blue)
        val semibold = ResourcesCompat.getFont(this, R.font.inter_semibold)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@VerifyActivity, R.color.ios_bg))
        }

        // Barra de navegación estilo iOS: Cancelar · Verificación · Listo
        val bar = FrameLayout(this).apply { setPadding(dp(8f), dp(6f), dp(8f), dp(6f)) }
        fun barButton(text: String, bold: Boolean, gravity: Int, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            if (bold) typeface = semibold
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            setOnClickListener { it.haptic(); onClick() }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity or Gravity.CENTER_VERTICAL)
        }
        bar.addView(barButton("Cancelar", false, Gravity.START) { setResult(RESULT_CANCELED); finish() })
        bar.addView(TextView(this).apply {
            text = when (platform) {
                Platform.YOUTUBE -> "Verificación"
                else -> platform.label
            }
            typeface = semibold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ContextCompat.getColor(this@VerifyActivity, R.color.ios_label))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        })
        bar.addView(barButton("Listo", true, Gravity.END) { done() })
        root.addView(bar)

        root.addView(TextView(this).apply {
            text = when (platform) {
                Platform.YOUTUBE -> "Si YouTube muestra una verificación o un captcha, resuélvelo aquí. " +
                    "Si te pide iniciar sesión, puedes hacerlo. Cuando veas YouTube normal, toca «Listo»."
                Platform.INSTAGRAM -> "Inicia sesión en Instagram (te recomendamos una cuenta secundaria). " +
                    "Cuando veas tu inicio de Instagram, toca «Listo». Tus datos solo se guardan en este teléfono."
                Platform.TIKTOK -> "Si TikTok muestra una verificación, resuélvela aquí. Iniciar sesión es opcional. " +
                    "Luego toca «Listo»."
                Platform.SPOTIFY -> "Las canciones de Spotify se buscan en YouTube. Si YouTube muestra una verificación, " +
                    "resuélvela aquí y toca «Listo»."
                Platform.FACEBOOK -> "Inicia sesión en Facebook solo si el video no es público o Facebook lo pide. " +
                    "Cuando termines, toca «Listo». Tus datos solo se guardan en este teléfono."
            }
            setTextColor(ContextCompat.getColor(this@VerifyActivity, R.color.ios_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16f), 0, dp(16f), dp(8f))
        })

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3f))
        }
        root.addView(progress)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        root.addView(web)

        setContentView(root)
        web.loadUrl(platform.verifyUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { setResult(RESULT_CANCELED); finish() }
            }
        })
    }

    private fun done() {
        if (platform == Platform.INSTAGRAM && !Cookies.webViewHasInstagramSession()) {
            Toast.makeText(this, "Aún no has iniciado sesión en Instagram.", Toast.LENGTH_LONG).show()
            return
        }
        val n = Cookies.exportFromWebView(this)
        if (n == 0) {
            Toast.makeText(this, "Aún no hay datos de YouTube. Espera a que cargue la página.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, if (platform == Platform.INSTAGRAM) "Sesión de Instagram guardada ✓"
                             else "Verificación guardada ✓", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
