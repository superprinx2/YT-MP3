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
        /** En el inicio se ve "Tu biblioteca"; en búsqueda, playlists y álbumes el contenido ocupa toda la pantalla. */
        private const val SPOTIFY_FIT_JS = """(function(){
  try{
    var s=document.getElementById('ds-css');
    if(!s){ s=document.createElement('style'); s.id='ds-css'; (document.head||document.documentElement).appendChild(s); }
    s.textContent = (location.pathname==='/' || location.pathname==='') ? '' : '#Desktop_LeftSidebar_Id{display:none!important}';
    var r=document.getElementById('onetrust-reject-all-handler'); if(r){ r.click(); }
  }catch(e){}
})();"""

        /** Junta las playlists de la barra lateral "Tu biblioteca" (la va bajando para que carguen todas). */
        private const val SPOTIFY_LIB_JS = """(function(){
  var found={}, order=[], tries=0, stable=0, last=-1;
  function add(url,name){ if(found[url]) return; found[url]=1; order.push({url:url,name:(name||'').trim()||'Playlist de Spotify'}); }
  function grab(){
    var side=document.getElementById('Desktop_LeftSidebar_Id')||document.querySelector('nav')||document;
    side.querySelectorAll('a[href*="/playlist/"]').forEach(function(a){
      var m=(a.getAttribute('href')||'').match(/\/playlist\/([A-Za-z0-9]+)/);
      if(m) add('https://open.spotify.com/playlist/'+m[1], a.textContent||a.getAttribute('title'));
    });
    side.querySelectorAll('[id*="spotify:playlist:"]').forEach(function(e){
      var m=e.id.match(/spotify:playlist:([A-Za-z0-9]+)/);
      if(m) add('https://open.spotify.com/playlist/'+m[1], e.textContent);
    });
    if(side.querySelector('a[href*="/collection/tracks"],[id*=":collection"]')) add('https://open.spotify.com/collection/tracks','Tus me gusta');
  }
  function scroller(){
    var side=document.getElementById('Desktop_LeftSidebar_Id'); if(!side) return null;
    var els=side.querySelectorAll('div'); var best=null;
    for(var i=0;i<els.length;i++){ var e=els[i]; if(e.scrollHeight>e.clientHeight+40){ var o=getComputedStyle(e).overflowY; if(o==='auto'||o==='scroll'){ best=e; } } }
    return best;
  }
  function step(){
    grab();
    var s=scroller(); if(s){ s.scrollTop=s.scrollTop+Math.max(200,s.clientHeight*0.8); }
    if(order.length===last){ stable++; } else { stable=0; last=order.length; }
    try{ DSBridge.libprogress(order.length); }catch(e){}
    tries++;
    if((order.length>0 && stable>=6) || tries>60){ DSBridge.library(JSON.stringify(order)); return; }
    setTimeout(step, 500);
  }
  step();
})();"""

        private const val SPOTIFY_JS = """(function(){
  var seen={}, order=[], stable=0, last=-1, tries=0;
  function grab(){
    document.querySelectorAll('[data-testid="tracklist-row"]').forEach(function(r){
      var a=r.querySelector('a[href*="/track/"]'); if(!a) return;
      var id=a.getAttribute('href').split('/track/')[1].split('?')[0];
      if(seen[id]) return;
      var arts=[].map.call(r.querySelectorAll('a[href*="/artist/"]'),function(x){return x.textContent.trim();});
      var m=(r.textContent.match(/\b\d{1,2}:\d{2}\b/g)||[]); var dur=0;
      if(m.length){ var p=m[m.length-1].split(':'); dur=(parseInt(p[0])*60+parseInt(p[1]))*1000; }
      seen[id]=1; order.push({id:id,title:a.textContent.trim(),artists:arts.join(', '),dur:dur});
    });
  }
  function step(){
    grab();
    var rows=document.querySelectorAll('[data-testid="tracklist-row"]');
    if(rows.length){ rows[rows.length-1].scrollIntoView({block:'end'}); }
    if(order.length===last){ stable++; } else { stable=0; last=order.length; }
    try{ DSBridge.progress(order.length); }catch(e){}
    tries++;
    if((order.length>0 && stable>=7) || order.length>=2000 || tries>70){
      var h=document.querySelector('[data-testid="entityTitle"]')||document.querySelector('main h1');
      var t=h? h.textContent.trim() : (document.title||'Spotify').split(' | ')[0];
      DSBridge.done(JSON.stringify(order), t);
      return;
    }
    setTimeout(step, 450);
  }
  step();
})();"""

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
                Platform.YOUTUBE -> "/watch" in path || "/shorts/" in path || ("/playlist" in path && url.contains("list="))
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
                if (s != "http" && s != "https") return true   // no abrir otras apps (intent://, spotify:, etc.)
                // al entrar o salir del inicio de sesión de Spotify se cambia de modo y se recarga con el modo correcto
                if (platform == Platform.SPOTIFY && req.isForMainFrame) {
                    val want = if (loginHost(req.url.toString())) mobileUa else desktopUa
                    if (view.settings.userAgentString != want) {
                        view.settings.userAgentString = want
                        view.loadUrl(req.url.toString()); return true
                    }
                }
                return false
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { bar.visibility = View.VISIBLE; check(url) }
            override fun onPageFinished(view: WebView, url: String?) {
                bar.visibility = View.GONE; check(url)
                val cb = pendingCollect
                if (cb != null && url != null && (url.contains("/playlist/") || url.contains("/album/") || url.contains("/collection/tracks"))) {
                    pendingCollect = null
                    view.postDelayed({ collectSpotify(cb) }, 1500)
                }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { check(url) }
        }
        w.setOnScrollChangeListener { _, _, y, _, oy -> onScroll?.invoke(y, oy) }
        w.addJavascriptInterface(Bridge(), "DSBridge")
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { bar.progress = p }
            // Spotify (y otros) piden "contenido protegido" (DRM Widevine) para su reproductor web:
            // sin este permiso muestran "Reproductor deshabilitado". Solo se concede eso, nada más.
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val ok = request.resources.filter { it == android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (ok.isNotEmpty()) request.grant(ok.toTypedArray()) else request.deny()
            }
        }
        frame.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(bar, FrameLayout.LayoutParams(MATCH, ctx.dp(3), Gravity.TOP))
        frame.addView(pill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ctx.dp(96) })
    }

    /** Avisa cuando cambia la página (para mostrar u ocultar la flecha de atrás). */
    var onNav: (() -> Unit)? = null
    fun canGoBack() = web?.canGoBack() == true

    /** ¿La dirección es del sitio de esa red? (para no guardar una página de TikTok como "la de YouTube"). */
    private fun belongs(p: Platform, url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        val doms = when (p) {
            Platform.YOUTUBE -> listOf("youtube.com", "youtu.be")
            Platform.SPOTIFY -> listOf("spotify.com")
            Platform.TIKTOK -> listOf("tiktok.com")
            Platform.INSTAGRAM -> listOf("instagram.com")
            Platform.FACEBOOK -> listOf("facebook.com", "fb.watch")
        }
        return doms.any { host == it || host.endsWith(".$it") }
    }

    private fun check(url: String?) {
        val p = platform ?: return
        if (url != null && belongs(p, url)) lastUrl[p] = url
        onNav?.invoke()
        if (p == Platform.SPOTIFY) web?.let { w ->
            w.evaluateJavascript(SPOTIFY_FIT_JS, null)
            w.postDelayed({ if (platform == Platform.SPOTIFY) w.evaluateJavascript(SPOTIFY_FIT_JS, null) }, 1800)
        }
        val ok = downloadable(p, url)
        if (ok && pill.visibility != View.VISIBLE) { pill.visibility = View.VISIBLE; pill.bounce() }
        else if (!ok) pill.visibility = View.GONE
        pill.text = if (p == Platform.SPOTIFY) "⬇  Descargar canciones" else "⬇  Descargar"
    }

    private val mobileUa by lazy { android.webkit.WebSettings.getDefaultUserAgent(ctx) }
    private val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Spotify en su versión de computadora (la de celular bloquea "Tu biblioteca" y pide su app). */
    /**
     * Spotify se ve en modo escritorio (para tener "Tu biblioteca"), pero sus páginas de inicio de sesión no:
     * en escritorio las casillas del código esperan un teclado físico y el teclado del celular no escribía.
     */
    private fun loginHost(url: String?): Boolean {
        val h = url?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: return false
        return listOf("accounts.spotify.com", "challenge.spotify.com", "accounts.google.com", "appleid.apple.com",
            "facebook.com", "m.facebook.com", "www.facebook.com").any { h == it || h.endsWith(".$it") } ||
            (h.endsWith("spotify.com") && (url.contains("/login") || url.contains("/challenge")))
    }

    private fun applyUaFor(w: WebView, url: String?) {
        if (platform != Platform.SPOTIFY) return
        val ua = if (loginHost(url)) mobileUa else desktopUa
        if (w.settings.userAgentString != ua) w.settings.userAgentString = ua
    }

    private fun applyUa(w: WebView, p: Platform) {
        val desk = p == Platform.SPOTIFY
        val ua = if (desk) desktopUa else mobileUa
        if (w.settings.userAgentString != ua) w.settings.userAgentString = ua
        w.settings.setSupportZoom(desk); w.settings.builtInZoomControls = desk; w.settings.displayZoomControls = false
    }

    fun show(p: Platform) {
        val w = ensureWeb()
        if (platform == p) return
        platform = p
        applyUa(w, p)
        w.stopLoading()
        field.setText("")
        field.hint = "Buscar en ${p.label} o pegar un enlace"
        pill.visibility = View.GONE
        val target = lastUrl[p]?.takeIf { belongs(p, it) } ?: home(p)
        applyUaFor(w, target)
        w.loadUrl(target)
    }

    var onScroll: ((Int, Int) -> Unit)? = null

    // ------------------------------------------------------------------ playlists de Spotify completas
    private var spotifyCb: ((String, List<VideoItem>) -> Unit)? = null

    private inner class Bridge {
        @android.webkit.JavascriptInterface fun progress(n: Int) {
            pill.post { pill.text = "Leyendo playlist… $n canciones" }
        }
        @android.webkit.JavascriptInterface fun libprogress(n: Int) {
            pill.post { pill.text = "Leyendo tu biblioteca… $n playlists" }
        }
        @android.webkit.JavascriptInterface fun library(json: String) {
            val list = runCatching {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i -> arr.getJSONObject(i).let { it.getString("name") to it.getString("url") } }
            }.getOrDefault(emptyList())
            pill.post { pill.text = "⬇  Descargar canciones"; val cb = libraryCb; libraryCb = null; cb?.invoke(list) }
        }
        @android.webkit.JavascriptInterface fun done(json: String, title: String) {
            val items = runCatching {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SpotifySource.track(o.getString("id"), o.getString("title"), o.optString("artists"), o.optLong("dur"), null)
                }
            }.getOrDefault(emptyList())
            pill.post {
                pill.text = "⬇  Descargar canciones"
                val cb = spotifyCb; spotifyCb = null
                cb?.invoke(title, items)
            }
        }
    }

    /**
     * Lee TODAS las canciones de la playlist o álbum de Spotify abierto (la página baja sola hasta el final).
     * La versión "embed" de Spotify solo da 100; así salen las 189, 500… que tenga.
     */
    private var pendingCollect: ((String, List<VideoItem>) -> Unit)? = null

    /** Abre una playlist o álbum de Spotify y, cuando carga, lee todas sus canciones. */
    fun openAndCollect(url: String, cb: (String, List<VideoItem>) -> Unit) {
        show(Platform.SPOTIFY)
        pendingCollect = cb
        go(url)
    }

    private var libraryCb: ((List<Pair<String, String>>) -> Unit)? = null

    /** Lee las playlists de "Tu biblioteca" (barra lateral de Spotify, con la sesión iniciada). Devuelve nombre → enlace. */
    fun collectSpotifyLibrary(cb: (List<Pair<String, String>>) -> Unit) {
        show(Platform.SPOTIFY)
        val w = ensureWeb()
        libraryCb = cb
        pill.text = "Leyendo tu biblioteca…"
        go(home(Platform.SPOTIFY))
        w.postDelayed({ if (libraryCb != null) w.evaluateJavascript(SPOTIFY_LIB_JS, null) }, 4000)
    }

    /** Se abandona una lectura que no terminó (para no dejar la respuesta pendiente). */
    fun cancelCollect() { pendingCollect = null; spotifyCb = null; libraryCb = null }

    fun collectSpotify(cb: (String, List<VideoItem>) -> Unit) {
        val w = web ?: return cb("", emptyList())
        spotifyCb = cb
        pill.text = "Leyendo playlist…"
        w.evaluateJavascript(SPOTIFY_JS, null)
    }
    fun hideField() { field.visibility = View.GONE }

    fun search(p: Platform, q: String) {
        val e = Uri.encode(q)
        show(p)
        go(when (p) {
            Platform.SPOTIFY -> "https://open.spotify.com/search/$e"
            Platform.TIKTOK -> "https://www.tiktok.com/search?q=$e"
            Platform.INSTAGRAM -> "https://www.instagram.com/explore/search/keyword/?q=$e"
            Platform.FACEBOOK -> "https://m.facebook.com/search/videos/?q=$e"
            Platform.YOUTUBE -> "https://m.youtube.com/results?search_query=$e"
        })
    }

    fun openLogin(p: Platform) { show(p); go(login(p)) }
    fun goHome() { platform?.let { go(home(it)) } }

    /** Abre una dirección con el modo correcto (escritorio en Spotify, móvil en su inicio de sesión). */
    private fun go(url: String) { val w = ensureWeb(); applyUaFor(w, url); w.loadUrl(url) }
    fun back(): Boolean { val w = web ?: return false; if (w.canGoBack()) { w.goBack(); return true }; return false }
    fun pause() {
        // que no siga sonando un video de la web al salir de la pestaña
        web?.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
        web?.onPause()
    }
    fun resume() { web?.onResume() }
}

@Suppress("unused") private fun ViewGroup.noop() = Unit
