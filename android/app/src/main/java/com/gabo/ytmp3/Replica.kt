package com.gabo.ytmp3

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Enlaces directos de audio/video para la vista previa (sin descargar). Se guardan en caché un rato. */
object Streams {
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val TTL = 3L * 60 * 60 * 1000

    suspend fun resolve(ctx: Context, item: VideoItem, video: Boolean): String = withContext(Dispatchers.IO) {
        val key = "${item.url}|$video"
        cache[key]?.takeIf { System.currentTimeMillis() - it.second < TTL }?.let { return@withContext it.first }
        App.ready.await()
        val target = if (item.platform == Platform.SPOTIFY) "ytsearch1:${item.url.removePrefix("spotify:")}" else item.url
        val cookies = Cookies.tempCopy(ctx)
        try {
            val req = YoutubeDLRequest(target)
                .addOption("-g")
                .addOption("-f", if (video) "b[ext=mp4][height<=480]/b[height<=480]/b" else "ba[ext=m4a]/ba/b")
                .addOption("--no-warnings")
                .addOption("--cache-dir", App.cacheDir(ctx).absolutePath)
            if (item.platform != Platform.SPOTIFY) req.addOption("--no-playlist")
            if (item.platform == Platform.YOUTUBE || item.platform == Platform.SPOTIFY)
                req.addOption("--extractor-args", "youtube:player_client=android_vr,tv_simply")
            if (cookies != null) req.addOption("--cookies", cookies.absolutePath)
            val url = YoutubeDL.getInstance().execute(req).out.lines().firstOrNull { it.startsWith("http") }
                ?: throw IllegalStateException("No se encontró la vista previa")
            cache[key] = url to System.currentTimeMillis()
            url
        } finally { cookies?.delete() }
    }
}

/** Reproductor de vista previa (independiente de la biblioteca). */
@OptIn(UnstableApi::class)
object Preview {
    private var player: ExoPlayer? = null

    fun play(ctx: Context, url: String, onEnd: () -> Unit) {
        stop()
        PlayerHub.service?.active?.pause()
        player = ExoPlayer.Builder(ctx.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_ENDED) onEnd()
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) = onEnd()
            })
            prepare(); play()
        }
    }

    fun fraction(): Float {
        val p = player ?: return 0f
        val d = p.duration
        return if (d > 0) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    fun stop() { player?.release(); player = null }

    /** Video en pantalla completa (se carga solo al tocar el botón). */
    fun showVideo(ctx: Context, title: String, load: suspend () -> String, scope: kotlinx.coroutines.CoroutineScope) {
        stop()
        PlayerHub.service?.active?.pause()
        val d = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val pv = PlayerView(ctx)
        val p = ExoPlayer.Builder(ctx).build()
        pv.player = p
        box.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
        val spin = ProgressBar(ctx).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
        box.addView(spin, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.CENTER))
        box.addView(ctx.text(title, 15f, Color.WHITE, 1).apply { setPadding(ctx.dp(16), ctx.dp(40), ctx.dp(56), 0) },
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        box.addView(ctx.text("✕", 22f, Color.WHITE, 2).apply {
            gravity = Gravity.CENTER; setOnClickListener { d.dismiss() }
        }, FrameLayout.LayoutParams(ctx.dp(52), ctx.dp(52), Gravity.TOP or Gravity.END).apply { topMargin = ctx.dp(28) })
        d.setContentView(box)
        d.setOnDismissListener { p.release() }
        d.show()
        scope.launch {
            try {
                val url = load()
                spin.visibility = View.GONE
                p.setMediaItem(MediaItem.fromUri(url)); p.prepare(); p.play()
            } catch (e: Exception) {
                spin.visibility = View.GONE
                android.widget.Toast.makeText(ctx, "No se pudo cargar el video", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}


/**
 * Réplica web de Spotify, TikTok, Instagram y Facebook: navegas su sitio dentro de la app (con
 * tu sesión, si iniciaste) y cuando abres algo descargable aparece el botón «Descargar».
 */
class WebReplica(private val ctx: Context, private val onDownload: (Platform, String) -> Unit) {
    val view = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
    private val frame = FrameLayout(ctx)
    private val field = android.widget.EditText(ctx)
    private var web: WebView? = null
    private var platform: Platform? = null
    private val lastUrl = HashMap<Platform, String>()
    private val pill: TextView
    private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progressTintList = android.content.res.ColorStateList.valueOf(BRAND_RED)
    }

    init {
        field.apply {
            isSingleLine = true; textSize = 15f
            setTextColor(ctx.col(R.color.ios_label)); setHintTextColor(ctx.col(R.color.ios_secondary))
            background = roundRect(ctx.col(R.color.ios_card), ctx.dp(12).toFloat())
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, _, _ ->
                val q = text.toString().trim()
                val p = platform
                if (q.isNotEmpty() && p != null) {
                    ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                    val link = Util.extractUrl(q)
                    if (link != null) onDownload(p, link) else search(p, q)
                }
                true
            }
        }
        view.addView(field, android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(8))
        })
        view.addView(frame, android.widget.LinearLayout.LayoutParams(MATCH, 0, 1f))
        pill = ctx.text("⬇  Descargar", 16f, Color.WHITE, 2).apply {
            gravity = Gravity.CENTER; minHeight = ctx.dp(50); setPadding(ctx.dp(26), 0, ctx.dp(26), 0)
            background = roundRect(BRAND_RED, ctx.dp(25).toFloat()); elevation = ctx.dp(8).toFloat()
            visibility = View.GONE
            pressable()
            setOnClickListener { v ->
                v.haptic(true)
                val p = platform ?: return@setOnClickListener
                val u = web?.url ?: return@setOnClickListener
                CookieManager.getInstance().flush()
                runCatching { Cookies.exportFromWebView(ctx) }
                onDownload(p, u)
            }
        }
    }

    companion object {
        fun home(p: Platform) = when (p) {
            Platform.SPOTIFY -> "https://open.spotify.com/"
            Platform.TIKTOK -> "https://www.tiktok.com/"
            Platform.INSTAGRAM -> "https://www.instagram.com/"
            Platform.FACEBOOK -> "https://m.facebook.com/watch/"
            Platform.YOUTUBE -> "https://m.youtube.com/"
        }
        fun login(p: Platform) = when (p) {
            Platform.SPOTIFY -> "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
            else -> p.verifyUrl
        }

        /** ¿La página abierta es algo que se puede descargar? */
        fun downloadable(p: Platform, url: String?): Boolean {
            val path = url?.let { runCatching { Uri.parse(it).path }.getOrNull() } ?: return false
            return when (p) {
                Platform.SPOTIFY -> listOf("/playlist/", "/album/", "/track/").any { it in path }
                Platform.TIKTOK -> "/video/" in path || "/photo/" in path
                Platform.INSTAGRAM -> listOf("/reel/", "/reels/", "/p/", "/tv/").any { path.startsWith(it) || "$it" in path }
                Platform.FACEBOOK -> listOf("/watch", "/reel/", "/videos/", "/share/v/", "/share/r/").any { it in path } && (url.contains("v=") || !path.endsWith("/watch/"))
                Platform.YOUTUBE -> "/watch" in path || "/shorts/" in path
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(): WebView = web ?: WebView(ctx).also { w ->
        web = w
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true; useWideViewPort = true
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val s = req.url.scheme ?: return true
                return s != "http" && s != "https"   // no abrir otras apps (intent://, spotify:, etc.)
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { bar.visibility = View.VISIBLE; check(url) }
            override fun onPageFinished(view: WebView, url: String?) { bar.visibility = View.GONE; check(url) }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { check(url) }
        }
        w.setOnScrollChangeListener { _, _, y, _, oy -> onScroll?.invoke(y, oy) }
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { bar.progress = p }
        }
        frame.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(bar, FrameLayout.LayoutParams(MATCH, ctx.dp(3), Gravity.TOP))
        frame.addView(pill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ctx.dp(96) })
    }

    private fun check(url: String?) {
        val p = platform ?: return
        if (url != null) lastUrl[p] = url
        val ok = downloadable(p, url)
        if (ok && pill.visibility != View.VISIBLE) { pill.visibility = View.VISIBLE; pill.bounce() }
        else if (!ok) pill.visibility = View.GONE
        pill.text = if (p == Platform.SPOTIFY) "⬇  Descargar canciones" else "⬇  Descargar"
    }

    fun show(p: Platform) {
        val w = ensureWeb()
        if (platform == p) return
        platform = p
        field.setText("")
        field.hint = "Buscar en ${p.label} o pegar un enlace"
        pill.visibility = View.GONE
        w.loadUrl(lastUrl[p] ?: home(p))
    }

    var onScroll: ((Int, Int) -> Unit)? = null
    fun hideField() { field.visibility = View.GONE }

    fun search(p: Platform, q: String) {
        val e = Uri.encode(q)
        show(p)
        ensureWeb().loadUrl(when (p) {
            Platform.SPOTIFY -> "https://open.spotify.com/search/$e"
            Platform.TIKTOK -> "https://www.tiktok.com/search?q=$e"
            Platform.INSTAGRAM -> "https://www.instagram.com/explore/search/keyword/?q=$e"
            Platform.FACEBOOK -> "https://m.facebook.com/search/videos/?q=$e"
            Platform.YOUTUBE -> "https://m.youtube.com/results?search_query=$e"
        })
    }

    fun openLogin(p: Platform) { show(p); ensureWeb().loadUrl(login(p)) }
    fun goHome() { platform?.let { ensureWeb().loadUrl(home(it)) } }
    fun back(): Boolean { val w = web ?: return false; if (w.canGoBack()) { w.goBack(); return true }; return false }
    fun pause() { web?.onPause() }
    fun resume() { web?.onResume() }
}

@Suppress("unused") private fun ViewGroup.noop() = Unit
