package com.triliumzro.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast

/**
 * Thin wrapper around the Trilium web UI (mobile layout).
 * Login, note tree, editor — all served by the server itself.
 * App-only additions live here.
 */
class MainActivity : Activity() {

    companion object {
        private val START_URL = BuildConfig.DEFAULT_SERVER
        // Not a hard-coded domain: whatever host the user configured is "ours",
        // everything else opens outside the app. Derived, so no private host
        // is baked into the source or the APK.
        private const val REQ_FILE = 1001


    }

    private lateinit var web: WebView

    private var aboutOpen = false
    private lateinit var bar: ProgressBar
    private lateinit var root: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var popup: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var taps = 0
    private var lastTap = 0L
    private var volTaps = 0
    private var lastVol = 0L
    private var volDownTaps = 0
    private var lastVolDown = 0L
    private var nativeFullscreen = false
    private var offline = false
    private var splash: View? = null
    /** 正在用原生遮罩盖住「切回内置登录页」这个过程。 */
    private var coverSwitch = false
    // password typed on the offline page: kept in memory until the server's
    // login form has actually taken it (or rejected it), never longer
    private var pendingPass: String? = null
    private var pendingRemember = true
    private var pendingSubmitted = false
    private var fillAttempts = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // One-time repair: an accidental 3x volume-up used to persist "styles off",
        // which is how the frosted-glass layer disappeared. Must run after
        // attach(), so it belongs here and not in a constructor block.
        try {
            val sp = getSharedPreferences("zrq", 0)
            if (!sp.getBoolean("inject-repair-95", false)) {
                sp.edit().putBoolean("inject", true)
                    .putBoolean("inject-repair-95", true).apply()
            }
        } catch (e: Exception) {
            android.util.Log.e("ZRQ", "inject repair failed", e)
        }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#1F1F1F"))

        web = WebView(this)
        // Different layer than CSS: the WebView's own scrollbar is drawn by the
        // native view on top of the page. Turning it off here means nothing is
        // painted over the page's right edge at all, and the page keeps its own
        // (thin) indicator. No page CSS is touched.
        web.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.isScrollbarFadingEnabled = true
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        val density = resources.displayMetrics.density
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progressDrawable?.setColorFilter(Color.parseColor("#5BC0BE"), PorterDuff.Mode.SRC_IN)
        val lp = FrameLayout.LayoutParams(-1, (3 * density).toInt())
        lp.topMargin = 0
        root.addView(bar, lp)
        bar.isClickable = true

        // Covers the WebView while a credential is being handed to the server,
        // so the server's own login page never flashes on the way in.
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val cover = FrameLayout(this)
        cover.setBackgroundColor(if (dark) Color.parseColor("#1F1F1F") else Color.parseColor("#FAFAFA"))
        cover.isClickable = true
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER
        val leaf = ImageView(this)
        leaf.setImageResource(R.drawable.logo_leaf)
        column.addView(leaf, LinearLayout.LayoutParams((96 * density).toInt(), (96 * density).toInt()))
        val spin = ProgressBar(this)
        val slp = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
        slp.topMargin = (24 * density).toInt()
        slp.gravity = Gravity.CENTER_HORIZONTAL
        column.addView(spin, slp)
        cover.addView(column, FrameLayout.LayoutParams(-1, -1))
        cover.visibility = View.GONE
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        splash = cover

        setContentView(root)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }

        // never keep a login: a change of password would otherwise be overridden
        // by a stale one. Drop anything an earlier build may have stored.
        getSharedPreferences("zrq", 0).edit().remove("user").remove("pass").apply()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        // surface page-side JS errors so the user can read them on the phone
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun fs(on: Boolean) {
                runOnUiThread { applyImmersive(on) }
            }

            @android.webkit.JavascriptInterface
            fun setAbout(open: Boolean) {
                aboutOpen = open
            }

            @android.webkit.JavascriptInterface
            fun retry() {
                runOnUiThread { connect() }
            }

            /** From the error page: open the sign-in screen (address prefilled). */
            @android.webkit.JavascriptInterface
            fun settings() {
                runOnUiThread { showLogin() }
            }

            @android.webkit.JavascriptInterface
            fun setServer(url: String?) {
                var u = (url ?: "").trim()
                if (u.isEmpty()) return
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u
                if (!u.endsWith("/")) u += "/"
                getSharedPreferences("zrq", 0).edit().putString("server", u).apply()
                runOnUiThread { connect() }
            }

            /**
             * Save the address and sign in with the credentials typed on the
             * offline page. They live in memory for this one attempt only: a
             * changed server password is never overridden by a stale one.
             */
            @android.webkit.JavascriptInterface
            fun login(url: String?, pass: String?, rem: String?) {
                var u = (url ?: "").trim()
                if (u.isEmpty()) u = serverUrl()
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u
                if (!u.endsWith("/")) u += "/"
                getSharedPreferences("zrq", 0).edit().putString("server", u).apply()
                pendingPass = pass ?: ""
                pendingRemember = (rem ?: "1") != "0"
                pendingSubmitted = false
                fillAttempts = 0
                runOnUiThread {
                    offline = false
                    bar.visibility = View.VISIBLE
                    bar.progress = 5
                    showSplash()
                    web.postDelayed({ hideSplash() }, 30000)
                    web.loadUrl(u)
                }
            }

            /** The server's login form took the password and got submitted. */
            @android.webkit.JavascriptInterface
            fun loginSubmitted() {
                runOnUiThread { pendingSubmitted = true }
            }

            /** Password rejected (or form never appeared): stop retrying. */
            @android.webkit.JavascriptInterface
            fun loginFailed() {
                runOnUiThread {
                    pendingPass = null
                    pendingSubmitted = false
                    fillAttempts = 0
                    hideSplash()
                    showLogin(true)
                }
            }

            /**
             * 服务器 SPA 自己跳到了它的 /login 页，而我们又不在「正在把密码交给服务器」
             * 的过程中（比如 cookie 过期、改了地址、点了退出）：切回我们自己的登录页，
             * 不让用户看到服务器自带的登录界面。
             */
            @android.webkit.JavascriptInterface
            fun needLogin() {
                runOnUiThread {
                    if (offline) return@runOnUiThread
                    if (pendingPass != null || pendingSubmitted) return@runOnUiThread
                    // 先盖上原生遮罩再换页：loadDataWithBaseURL 渲染完之前，
                    // WebView 里还是服务器页面，不盖就会闪一下。
                    showLogin(cover = true)
                }
            }

            /** Logged in: drop the in-memory password. */
            @android.webkit.JavascriptInterface
            fun loggedIn() {
                runOnUiThread {
                    pendingPass = null
                    pendingSubmitted = false
                    fillAttempts = 0
                    hideSplash()
                }
            }

            @android.webkit.JavascriptInterface
            fun log(msg: String?) {
                val text = (msg ?: "").trim()
                if (text.isEmpty()) return
                // errors are drawn inside the page now (a toast vanished before
                // it could be read); keep logcat for debugging
                android.util.Log.e("ZRQ", text)
            }
        }, "ZRQ")

        web.webViewClient = object : WebViewClient() {
            // the sign-in page is loaded from a string, so its logo comes from
            // a virtual host we answer ourselves
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                if (request.url.host == "zro.local") {
                    return android.webkit.WebResourceResponse(
                        "image/png", "utf-8", resources.openRawResource(R.drawable.logo_leaf)
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean = handleUrl(request.url)

            @Deprecated("compat")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleUrl(Uri.parse(url))

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                val host = Uri.parse(error.url ?: "").host ?: ""
                if (isOwnHost(host)) handler.proceed() else handler.cancel()
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                // only the page itself matters; a missing sub-resource is harmless
                if (!request.isForMainFrame) return
                showError()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                response: android.webkit.WebResourceResponse
            ) {
                // a reverse proxy in front of a dead server answers 502/503:
                // that is the same situation, show the same page
                if (!request.isForMainFrame) return
                if (response.statusCode >= 500) showError()
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (url.startsWith("about:")) return   // our own offline page
                bar.progress = 5
                bar.visibility = View.VISIBLE
                view.evaluateJavascript(versionPrelude(), null)
                view.evaluateJavascript(Inject.SHIM, null)
                view.evaluateJavascript(LOGIN_GUARD, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                bar.visibility = View.GONE
                if (url.startsWith("about:")) {
                    if (coverSwitch) { coverSwitch = false; hideSplash() }
                    return   // our own offline page
                }
                if (pendingPass == null) hideSplash()
                view.evaluateJavascript(versionPrelude(), null)
                view.evaluateJavascript(Inject.SHIM, null)
                view.evaluateJavascript(LOGIN_GUARD, null)
                if (stylesOn()) view.evaluateJavascript(Inject.STYLES, null)
                val bn = getSharedPreferences("zrq", 0).getString("banner", null)
                if (bn != null) {
                    getSharedPreferences("zrq", 0).edit().remove("banner").apply()
                    val text = bn + "（本页样式" + (if (stylesOn()) "已注入" else "未注入") + "）"
                    view.evaluateJavascript(
                        "setTimeout(function(){window.__zrqBanner&&window.__zrqBanner(" +
                            org.json.JSONObject.quote(text) + ");},500)", null
                    )
                }

                // The SPA builds its login form after the page "finishes",
                // so a single fill is too early. Submitted and we are off the
                // login route? Then the password is spent. Otherwise let the
                // script keep looking for the form and submit it once.
                if (pendingSubmitted && !url.contains("login", ignoreCase = true)) {
                    pendingPass = null
                    pendingSubmitted = false
                    fillAttempts = 0
                    hideSplash()
                }
                val pp = pendingPass
                if (pp != null && fillAttempts < 3) {
                    fillAttempts++
                    view.evaluateJavascript(fillLoginScript(pp, pendingRemember), null)
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // popups (window.open) — Trilium uses them for some panels; without
            // this the call silently returns null and the UI appears frozen
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val child = WebView(this@MainActivity)
                child.settings.javaScriptEnabled = true
                child.settings.domStorageEnabled = true
                child.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        web.loadUrl(request.url.toString())
                        return true
                    }

                    @Deprecated("compat")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                        web.loadUrl(url)
                        return true
                    }
                }
                popup = child
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                bar.visibility = View.GONE
                web.visibility = View.GONE
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
            }

            override fun onHideCustomView() {
                val v = customView ?: return
                root.removeView(v)
                web.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val intent = params.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    startActivityForResult(
                        Intent.createChooser(intent, "选择文件"), REQ_FILE
                    )
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, ua, disposition, mime, size ->
            try {
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    Toast.makeText(this, "这种文件请在网页里长按保存", Toast.LENGTH_SHORT).show()
                    return@setDownloadListener
                }
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mime)
                CookieManager.getInstance().getCookie(url)?.let {
                    req.addRequestHeader("Cookie", it)
                }
                req.addRequestHeader("User-Agent", ua)
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    android.webkit.URLUtil.guessFileName(url, disposition, mime)
                )
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else if (serverUrl().isBlank()) {
            // nothing configured yet: show the sign-in screen, not an error
            showLogin()
        } else {
            web.loadUrl(serverUrl())
        }
    }

    /** Server address, changeable from the offline page. */
    private fun serverUrl(): String =
        getSharedPreferences("zrq", 0).getString("server", START_URL)?.takeIf { it.isNotBlank() }
            ?: START_URL

    /** True when the host belongs to the configured server (or a subdomain of it). */
    private fun isOwnHost(host: String): Boolean {
        if (host.isBlank()) return false
        val own = runCatching { Uri.parse(serverUrl()).host ?: "" }.getOrDefault("")
        if (own.isBlank()) return false
        return host == own || host.endsWith("." + own)
    }

    /** Load the server again (retry / after changing the address). */
    private fun showSplash() {
        splash?.visibility = View.VISIBLE
    }

    private fun hideSplash() {
        splash?.visibility = View.GONE
    }

    private fun connect() {
        offline = false
        bar.visibility = View.VISIBLE
        bar.progress = 5
        web.loadUrl(serverUrl())
    }

    /** Sign-in / connection page. The layout never changes; a failure only
     *  adds the popup panel on top of it. */
    private fun showLogin(msg: Boolean = false, cover: Boolean = false) {
        if (cover) {
            coverSwitch = true
            showSplash()
        } else {
            hideSplash()
        }
        offline = true
        bar.visibility = View.GONE
        web.loadDataWithBaseURL(null, offlinePage(msg), "text/html", "utf-8", null)
        if (cover) {
            // 本地页渲染很快；拿不到 onPageFinished 时也兜个底
            web.postDelayed({ if (coverSwitch) { coverSwitch = false; hideSplash() } }, 500)
        }
    }

    private fun showError() = showLogin(true)

    private fun offlinePage(warn: Boolean): String {
        val url = serverUrl().replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
        val panel = if (warn) """
  <div class="backdrop"></div>
  <div class="panel">
    <div class="msg">无法连接服务器，<br>请检查网络连接或服务器设置和密码！</div>
    <button class="ok" onclick="var b=document.querySelector('.backdrop');if(b)b.remove();this.parentNode.remove()">知道了</button>
  </div>""" else ""
        return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<style>
*{box-sizing:border-box}
html,body{margin:0;height:100%}
body{font-family:Inter,-apple-system,"PingFang SC","Noto Sans SC",sans-serif;
  background:#fafafa;color:#222;display:flex;align-items:center;justify-content:center;
  padding:24px;-webkit-tap-highlight-color:transparent}
@media (prefers-color-scheme:dark){body{background:#1f1f1f;color:#e8e8e8}}
.card{width:100%;max-width:360px;text-align:center}
.logo{width:132px;height:132px;margin:0 auto 14px;display:block}
h1{font-size:22px;font-weight:600;letter-spacing:.2px;margin:0 0 26px}
h2{font-size:15px;font-weight:400;opacity:.55;margin:0 0 20px}
form{text-align:left}
label{display:block;font-size:13px;opacity:.55;margin:0 0 7px}
input{width:100%;font:inherit;font-size:15px;padding:13px 14px;border-radius:12px;
  margin-bottom:16px;border:1px solid rgba(128,128,128,.35);background:transparent;
  color:inherit}
button{font:inherit;border:0;border-radius:12px;padding:14px 18px;width:100%;cursor:pointer}
.primary{background:#1E9BF7;color:#fff;font-weight:600;font-size:16px}
.primary:active{opacity:.85}
.primary + .primary{margin-top:18px}
.row{display:flex;align-items:center;justify-content:space-between;margin:-2px 2px 20px}
.row span{font-size:15px}
.sw{width:46px;height:26px;border-radius:13px;background:rgba(128,128,128,.35);position:relative;
  transition:background .18s;-webkit-tap-highlight-color:transparent}
.sw.on{background:#1E9BF7}
.sw i{position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:#fff;
  transition:left .18s;box-shadow:0 1px 3px rgba(0,0,0,.25)}
.sw.on i{left:23px}
.backdrop{position:fixed;inset:0;background:rgba(0,0,0,.42);z-index:8}
.panel{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);
  width:min(316px,calc(100% - 40px));
  background:#fff;color:#222;border-radius:16px;padding:22px 20px 16px;z-index:9;
  box-shadow:0 12px 48px rgba(0,0,0,.32)}
@media (prefers-color-scheme:dark){.panel{background:#2b2b2b;color:#eee}}
.panel .msg{font-size:15px;line-height:1.6;text-align:center;margin-bottom:18px}
.panel .ok{background:#1E9BF7;color:#fff;font-weight:600;font-size:15px;padding:12px}
</style></head><body>
<div class="card">
  <img class="logo" src="https://zro.local/logo.png" alt="">
  <h1>TriliumZro</h1>
  <h2>服务器设置 / 登录</h2>
  <form onsubmit="go();return false">
    <label>服务器地址</label>
    <input id="srv" value="$url" placeholder="https://your-server/" autocapitalize="off" autocorrect="off" spellcheck="false">
    <label>密码</label>
    <input id="p" type="password">
    <div class="row"><span>记住密码</span><span class="sw on" id="rem" onclick="tog()"><i></i></span></div>
    <button class="primary" type="submit">保存并登录</button>
    <button class="primary" type="button" onclick="re()">重试</button>
  </form>
  $panel
</div>
<script>
var rem=1;
function tog(){
  rem=rem?0:1;
  document.getElementById('rem').classList.toggle('on',!!rem);
}
function go(){
  var u=document.getElementById('srv').value||'';
  var p=document.getElementById('p').value||'';
  if(!u.trim())return;
  ZRQ.login(u,p,String(rem));
}
function re(){
  var u=document.getElementById('srv').value||'';
  var p=document.getElementById('p').value||'';
  if(!u.trim()){ZRQ.retry();return;}
  if(p)ZRQ.login(u,p,String(rem));else ZRQ.setServer(u);
}
</script>
</body></html>"""
    }

    /**
     * 常驻页面守卫：一旦 SPA 路由到服务器自己的 /login（且没有密码正在提交），
     * 通过桥通知原生层切回我们的登录页。SPA 切换路由不触发 onPageFinished，
     * 所以必须在页面内用定时器盯着。
     */
    /**
     * 常驻页面守卫。两件事：
     *  1) 尽早塞一条 CSS，把服务器自带登录页遮住（body.setup 只有 login/setup/
     *     set_password 三个 chunk 会加，而 .login-container 仅登录页有），
     *     所以官方登录界面从渲染的第一帧就不会露出来；
     *  2) 一旦确认是登录页，通过桥通知原生层切回我们自己的登录页。
     * 判据用 DOM 而不是 URL：Trilium 的 /login 会 302 到 "/?login"，SPA 停在 "/"。
     */
    private val LOGIN_GUARD = """
        (function(){
          if (window.__zrqGuardOn) return 'busy';
          window.__zrqGuardOn = 1;
          var HIDE_ID = 'zrq-login-hide';
          function ensureHide(){
            try{
              if (window.__zrqHideOff) return;
              if (document.getElementById(HIDE_ID)) return;
              var st = document.createElement('style');
              st.id = HIDE_ID;
              st.textContent = 'body.setup .login-container{visibility:hidden !important}';
              (document.head || document.documentElement).appendChild(st);
            }catch(e){}
          }
          function onLoginScreen(){
            try{
              var pr = location.protocol || '';
              if (pr !== 'http:' && pr !== 'https:') return false;
              if (document.querySelector('.login-container')) return true;
              var p = (location.pathname || '');
              while (p.length > 1 && p.charAt(p.length - 1) === '/') p = p.slice(0, -1);
              return p.slice(-6) === '/login';
            }catch(e){}
            return false;
          }
          function chk(){
            try{
              ensureHide();
              if (!onLoginScreen()){ window.__zrqGuardFired = 0; return; }
              if (window.__zrqFill) return;
              if (window.__zrqGuardFired) return;
              if (!document.querySelector('input[type=password]')) return;
              window.__zrqGuardFired = 1;
              ZRQ.needLogin();
            }catch(e){}
          }
          ensureHide();
          setTimeout(ensureHide, 150);
          setTimeout(chk, 400);
          setInterval(chk, 500);
          // 兜底：5 秒后还停在服务器登录页（说明切换没成功），把遮罩永久撤掉，
          // 免得用户面对一片空白、连手动登录都做不到。
          setTimeout(function(){
            try{
              if (document.querySelector('.login-container')){
                window.__zrqHideOff = 1;
                var s = document.getElementById(HIDE_ID);
                if (s) s.remove();
              }
            }catch(e){}
          }, 5000);
          return 'ok';
        })()
    """.trimIndent()

    /** Fill Trilium's login form (password only) and submit it. */
    /**
     * The server renders its own password form after the page has loaded, so
     * this keeps polling for it, fills it once, turns on "记住我" and submits.
     * It reports back through the bridge so the password in memory can be
     * dropped as soon as it is used or rejected.
     */
    private fun fillLoginScript(pass: String, remember: Boolean): String {
        val p = org.json.JSONObject.quote(pass)
        val rem = if (remember) "true" else "false"
        return "(function(){" +
            "if(window.__zrqFill)return 'busy';" +
            "window.__zrqFill=1;" +
            "var pass=" + p + ",submitted=false,started=Date.now(),done=false;" +
            "function set(el,v){try{var d=Object.getOwnPropertyDescriptor(el.constructor.prototype,'value');" +
            "if(d&&d.set)d.set.call(el,v);else el.value=v;}catch(e){el.value=v;}" +
            "try{el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}}" +
            "function sbtn(){var b=document.querySelector('button[type=submit]');if(b)return b;" +
            "var a=[].slice.call(document.querySelectorAll('button'));" +
            "for(var i=0;i<a.length;i++){if(/登录|log\\s?in|sign\\s?in/i.test(a[i].textContent||''))return a[i];}" +
            "return null;}" +
            "function remember(){var c=document.querySelector('input[type=checkbox]');" +
            "try{if(c&&!!c.checked!==" + rem + ")c.click();}catch(e){}}" +
            "var iv=setInterval(function(){try{" +
            "if(done)return;" +
            "var el=document.querySelector('input[type=password]');" +
            "if(el&&!submitted){" +
            "remember();set(el,pass);var f=el.closest('form');" +
            "try{if(f&&f.requestSubmit)f.requestSubmit();" +
            "else{var b=sbtn();if(b)b.click();else if(f)f.submit();}" +
            "submitted=true;try{ZRQ.loginSubmitted();}catch(e){}}catch(e){}" +
            "return;}" +
            "if(submitted){" +
            "var t=(document.body&&document.body.innerText)||'';" +
            "if(/密码不正确|incorrect password|wrong password/i.test(t)){" +
            "clearInterval(iv);window.__zrqFill=0;done=true;try{ZRQ.loginFailed();}catch(e){}return;}" +
            "if(!document.querySelector('input[type=password]')){" +
            "clearInterval(iv);window.__zrqFill=0;done=true;try{ZRQ.loggedIn();}catch(e){}return;}}" +
            "if(!submitted&&Date.now()-started>8000){" +
            "clearInterval(iv);window.__zrqFill=0;done=true;" +
            "try{if(/login/i.test(location.href))ZRQ.loginFailed();else ZRQ.loggedIn();}catch(e){}return;}" +
            "if(Date.now()-started>20000){clearInterval(iv);window.__zrqFill=0;done=true;try{ZRQ.loginFailed();}catch(e){}}" +
            "}catch(e){clearInterval(iv);window.__zrqFill=0;done=true;}},250);" +
            "return 'armed';})()"
    }

    /** Fullscreen from the page: hide the system bars so it is actually visible. */
    @Suppress("DEPRECATION")
    private fun applyImmersive(on: Boolean) {
        nativeFullscreen = on
        val v = window.decorView
        if (on) {
            v.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } else {
            v.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    @Deprecated("legacy back handling")
    override fun onBackPressed() {
        if (customView != null) {
            root.removeView(customView)
            web.visibility = View.VISIBLE
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
            return
        }
        if (nativeFullscreen) {
            applyImmersive(false)
            web.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null)
            return
        }
        // our injected "About" page is a page-level modal: back closes it first
        if (aboutOpen) {
            web.evaluateJavascript("window.__zrqCloseAbout&&window.__zrqCloseAbout()", null)
            return
        }
        if (offline) {
            super.onBackPressed()
            return
        }
        if (web.canGoBack()) {
            web.goBack()
            return
        }
        super.onBackPressed()
    }

    private fun handleUrl(uri: Uri): Boolean {
        return handleUrlImpl(uri)
    }

    /** Exposes the APK's own version so the in-page "About" line can show it. */
    private fun versionPrelude(): String =
        "window.__ZRQ_APP_VER='" + BuildConfig.VERSION_NAME +
            "';window.__ZRQ_APP_CODE='" + BuildConfig.VERSION_CODE + "';"

    private fun stylesOn(): Boolean =
        getSharedPreferences("zrq", 0).getBoolean("inject", true)

    private fun handleUrlImpl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        val host = uri.host ?: ""
        if ((scheme == "https" || scheme == "http") && isOwnHost(host)) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val cb = fileCallback
            fileCallback = null
            if (cb == null) return
            if (resultCode != RESULT_OK || data == null) {
                cb.onReceiveValue(null)
                return
            }
            val clip = data.clipData
            val uris: Array<Uri>? = when {
                clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
            cb.onReceiveValue(uris)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        // NOTE: web.onPause() pauses JS timers, which also kills the page's
        // websocket: after coming back the client never resubscribed, so new
        // notes only appeared after a manual reload.
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // nudge the page so it reconnects / re-fetches after being in background
        web.evaluateJavascript(
            "try{document.dispatchEvent(new Event('visibilitychange'));" +
            "window.dispatchEvent(new Event('online'));" +
            "window.dispatchEvent(new Event('focus'));" +
            "if(window.__zrqGlass&&window.__zrqGlass.refresh)window.__zrqGlass.refresh(true);}catch(e){}",
            null
        )
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
