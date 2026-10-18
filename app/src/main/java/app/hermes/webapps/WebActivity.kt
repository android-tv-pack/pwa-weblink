package app.hermes.webapps

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** The pointer you steer with the arrow keys. It fades out when idle. */
class CursorView(ctx: android.content.Context) : View(ctx) {
    var px = 0f; var py = 0f
    private var shownAt = 0L
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 10, 10, 20); style = Paint.Style.STROKE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 77, 208, 225); style = Paint.Style.STROKE }
    private val d = ctx.resources.displayMetrics.density
    private val hide = Runnable { invalidate() }
    var on = true

    fun poke() { shownAt = SystemClock.uptimeMillis(); invalidate(); removeCallbacks(hide); postDelayed(hide, 4200) }

    override fun onDraw(c: Canvas) {
        if (!on) return
        val age = SystemClock.uptimeMillis() - shownAt
        if (age > 4000) return
        val a = if (age < 3000) 1f else 1f - (age - 3000) / 1000f
        fill.alpha = (255 * a).toInt(); edge.alpha = (230 * a).toInt(); ring.alpha = (90 * a).toInt()
        edge.strokeWidth = 2f * d; ring.strokeWidth = 5f * d
        c.drawCircle(px, py, 13f * d, ring)
        c.drawCircle(px, py, 8f * d, fill)
        c.drawCircle(px, py, 8f * d, edge)
        if (a < 1f) postInvalidateDelayed(40)
    }
}

class WebActivity : Activity() {
    private lateinit var app: WebApp
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var splash: FrameLayout
    private lateinit var bar: View
    private var custom: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private val h = Handler(Looper.getMainLooper())
    private var cx = 0f; private var cy = 0f
    private var downAt = 0L
    private var pressing = false
    private var failedUrl: String? = null
    private var menu: AlertDialog? = null
    private var defaultUa = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val a = resolveApp(intent)
        if (a == null) { Toast.makeText(this, "That web app no longer exists", Toast.LENGTH_LONG).show(); finish(); return }
        app = a
        build()
        load(app.url)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        val a = resolveApp(i) ?: return
        if (a.id != app.id) {
            app = a; applySettings(); decorate(); showSplash(); load(app.url)
        }
    }

    private fun resolveApp(i: Intent): WebApp? {
        Apps.get(this, i.getStringExtra("id"))?.let { return it }
        val d = i.dataString ?: i.getStringExtra("url") ?: return null
        val u = Fetcher.normalize(d)
        return Apps.all(this).firstOrNull { it.url == u } ?: WebApp("tmp", Uri.parse(u).host ?: "Web", u, "", 0xFF203050.toInt())
    }

    // ------------------------------------------------------------------ building the screen

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun build() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        root.addView(web, ViewGroup.LayoutParams(-1, -1))
        defaultUa = web.settings.userAgentString

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        s.setSupportZoom(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:")) return false
                return true     // intent:, mailto:, market: ... are ignored on a TV
            }
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) { setBar(0.05f) }
            override fun onPageFinished(v: WebView, url: String) { setBar(1f); applyZoom(); hideSplash() }
            override fun onPageCommitVisible(v: WebView, url: String) { hideSplash() }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) showError(r.url.toString(), e.description.toString())
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { setBar(p / 100f); if (p >= 70) hideSplash() }
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                if (custom != null) { cb.onCustomViewHidden(); return }
                custom = v; customCb = cb
                v.setBackgroundColor(Color.BLACK)
                root.addView(v, root.indexOfChild(cursor).coerceAtLeast(0), FrameLayout.LayoutParams(-1, -1))
                web.visibility = View.INVISIBLE
            }
            override fun onHideCustomView() { leaveCustom() }
            override fun onPermissionRequest(r: PermissionRequest) {
                // DRM video is fine; camera and microphone are not offered on a TV
                val ok = r.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (ok.isNotEmpty()) r.grant(ok.toTypedArray()) else r.deny()
            }
            override fun onCreateWindow(v: WebView, dialog: Boolean, gesture: Boolean, msg: android.os.Message): Boolean {
                // pop-ups open in the same app window
                val t = WebView(this@WebActivity)
                t.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(w: WebView, r: WebResourceRequest): Boolean { web.loadUrl(r.url.toString()); return true }
                }
                (msg.obj as WebView.WebViewTransport).webView = t
                msg.sendToTarget()
                return true
            }
            override fun onConsoleMessage(m: ConsoleMessage?) = true
        }

        cursor = CursorView(this)
        root.addView(cursor, ViewGroup.LayoutParams(-1, -1))

        bar = View(this)
        root.addView(bar, FrameLayout.LayoutParams(0, (3 * resources.displayMetrics.density).toInt(), Gravity.TOP or Gravity.START))

        splash = FrameLayout(this)
        root.addView(splash, ViewGroup.LayoutParams(-1, -1))
        setContentView(root)
        applySettings()
        decorate()
        showSplash()
        immersive()
        root.post { cx = root.width / 2f; cy = root.height / 2f; cursor.px = cx; cursor.py = cy }
    }

    private fun decorate() {
        val ic = Apps.icon(this, app.id)
        try {
            if (Build_OK) setTaskDescription(ActivityManager.TaskDescription(app.name, ic, app.color or 0xFF000000.toInt()))
        } catch (_: Exception) {}
    }
    private val Build_OK get() = android.os.Build.VERSION.SDK_INT >= 21

    private fun showSplash() {
        splash.removeAllViews()
        splash.visibility = View.VISIBLE
        splash.alpha = 1f
        splash.setBackgroundColor(app.color or 0xFF000000.toInt())
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        Apps.icon(this, app.id)?.let { bm ->
            val iv = ImageView(this); iv.setImageBitmap(bm)
            val sz = (110 * resources.displayMetrics.density).toInt()
            col.addView(iv, LinearLayout.LayoutParams(sz, sz))
        }
        val lum = Color.red(app.color) * 0.3 + Color.green(app.color) * 0.59 + Color.blue(app.color) * 0.11
        col.addView(TextView(this).apply {
            text = app.name; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(if (lum > 150) Color.BLACK else Color.WHITE); setPadding(0, (14 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        splash.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        h.removeCallbacks(splashTimeout); h.postDelayed(splashTimeout, 12000)
        bar.setBackgroundColor(Color.WHITE)
    }
    private val splashTimeout = Runnable { hideSplash() }
    private fun hideSplash() {
        if (splash.visibility != View.VISIBLE) return
        h.removeCallbacks(splashTimeout)
        splash.animate().alpha(0f).setDuration(260).withEndAction { splash.visibility = View.GONE }.start()
    }

    private fun setBar(f: Float) {
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        lp.width = (root.width * f).toInt()
        bar.layoutParams = lp
        bar.visibility = if (f >= 1f) View.GONE else View.VISIBLE
        bar.setBackgroundColor(Color.argb(210, 255, 255, 255))
    }

    /** the settings that can change per app: layout (user agent), pointer */
    private fun applySettings() {
        val ua = defaultUa
        val chrome = Regex("Chrome/([0-9.]+)").find(ua)?.groupValues?.get(1) ?: "124.0.0.0"
        web.settings.userAgentString = if (app.desktop)
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        else ua.replace("; wv", "").replace("Version/4.0 ", "")
        cursor.on = app.cursor
        cursor.invalidate()
    }

    private fun applyZoom() {
        if (app.zoom == 100) return
        web.evaluateJavascript("document.documentElement.style.zoom='${app.zoom / 100f}'", null)
    }

    private fun load(u: String) { failedUrl = null; web.loadUrl(u) }

    private fun showError(url: String, why: String) {
        failedUrl = url
        hideSplash()
        val html = "<html><body style='background:#0b1020;color:#fff;font-family:sans-serif;text-align:center;padding-top:22vh'>" +
            "<h1 style='font-weight:300'>Can't open this page</h1><p style='color:#9fb4c4;font-size:22px'>${Uri.parse(url).host}<br>$why</p>" +
            "<p style='color:#6f8294;font-size:18px'>Press Back, or hold Back for the menu and choose Reload</p></body></html>"
        web.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) immersive() }
    override fun onResume() { super.onResume(); web.onResume(); web.resumeTimers() }
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }
    override fun onDestroy() { try { web.destroy() } catch (_: Exception) {}; super.onDestroy() }

    private fun leaveCustom() {
        val v = custom ?: return
        root.removeView(v); custom = null
        customCb?.onCustomViewHidden(); customCb = null
        web.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ the remote

    private fun isMenuKey(k: Int) = k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_SETTINGS ||
        k == KeyEvent.KEYCODE_GUIDE || k == KeyEvent.KEYCODE_PROG_YELLOW || k == KeyEvent.KEYCODE_TV_CONTENTS_MENU

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val k = ev.keyCode
        if (menu?.isShowing == true) return super.dispatchKeyEvent(ev)
        if (isMenuKey(k)) { if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) showMenu(); return true }
        if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(ev)       // handled in onKeyDown / onKeyUp below
        if (app.cursor && custom == null) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) moveCursor(k, ev.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // a press is a mouse click; holding it keeps the button down (drag, long press)
                    if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) press(true)
                    else if (ev.action == KeyEvent.ACTION_UP) press(false)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun onKeyDown(k: Int, ev: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK) { ev.startTracking(); return true }
        return super.onKeyDown(k, ev)
    }

    override fun onKeyLongPress(k: Int, ev: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK) { showMenu(); return true }
        return super.onKeyLongPress(k, ev)
    }

    override fun onKeyUp(k: Int, ev: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK && ev.isTracking && !ev.isCanceled) {
            when {
                custom != null -> leaveCustom()
                web.canGoBack() -> web.goBack()
                else -> finish()
            }
            return true
        }
        return super.onKeyUp(k, ev)
    }

    private fun moveCursor(k: Int, rep: Int) {
        val d = resources.displayMetrics.density
        val step = (5f + minOf(rep, 24) * 1.3f) * d
        val w = root.width.toFloat(); val hgt = root.height.toFloat()
        val edge = 22f * d
        var dx = 0f; var dy = 0f
        when (k) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step; KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step; else -> dy = step
        }
        // pushing against a screen edge scrolls the page under the pointer
        if (dy > 0 && cy >= hgt - edge) { scroll(0f, -1f); return }
        if (dy < 0 && cy <= edge) { scroll(0f, 1f); return }
        if (dx > 0 && cx >= w - edge) { scroll(-1f, 0f); return }
        if (dx < 0 && cx <= edge) { scroll(1f, 0f); return }
        cx = (cx + dx).coerceIn(0f, w - 1); cy = (cy + dy).coerceIn(0f, hgt - 1)
        cursor.px = cx; cursor.py = cy; cursor.poke()
        val now = SystemClock.uptimeMillis()
        if (pressing) {
            val e = MotionEvent.obtain(downAt, now, MotionEvent.ACTION_MOVE, cx, cy, 0); e.source = InputDevice.SOURCE_TOUCHSCREEN
            web.dispatchTouchEvent(e); e.recycle()
        } else {
            val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0); e.source = InputDevice.SOURCE_MOUSE
            web.dispatchGenericMotionEvent(e); e.recycle()
        }
    }

    private fun scroll(h: Float, v: Float) {
        cursor.poke()
        val now = SystemClock.uptimeMillis()
        val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        // scroll at the pointer's column (rows for sideways scrolling) but across the middle of the screen, so dialogs and
        // panels that don't reach the screen edge still scroll
        val sx = if (v != 0f) cx else root.width / 2f
        val sy = if (v != 0f) root.height / 2f else cy
        val pc = MotionEvent.PointerCoords().apply { x = sx; y = sy; setAxisValue(MotionEvent.AXIS_VSCROLL, v * 2f); setAxisValue(MotionEvent.AXIS_HSCROLL, h * 2f) }
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(pp), arrayOf(pc), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        web.dispatchGenericMotionEvent(e); e.recycle()
    }

    private fun press(down: Boolean) {
        cursor.poke()
        val now = SystemClock.uptimeMillis()
        if (down) { downAt = now; pressing = true } else if (!pressing) return else pressing = false
        val e = MotionEvent.obtain(downAt, now, if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, cx, cy, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(e); e.recycle()
    }

    // ------------------------------------------------------------------ menu

    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun showMenu() {
        val items = arrayOf(
            "Reload",
            "Go to the start page",
            "Open another address…",
            "Pointer (arrow keys move a mouse): " + (if (app.cursor) "On" else "Off"),
            "Layout: " + (if (app.desktop) "Desktop" else "Mobile"),
            "Zoom in  (now ${app.zoom}%)",
            "Zoom out  (now ${app.zoom}%)",
            "Close this app"
        )
        menu = dlg().setTitle(app.name).setItems(items) { _, i ->
            when (i) {
                0 -> { if (failedUrl != null) load(failedUrl!!) else web.reload() }
                1 -> load(app.url)
                2 -> askAddress()
                3 -> { app.cursor = !app.cursor; save(); applySettings(); Toast.makeText(this, if (app.cursor) "Pointer on" else "Pointer off: arrow keys move focus", Toast.LENGTH_SHORT).show() }
                4 -> { app.desktop = !app.desktop; save(); applySettings(); web.reload() }
                5 -> { app.zoom = minOf(200, app.zoom + 10); save(); applyZoom(); Toast.makeText(this, "Zoom ${app.zoom}%", Toast.LENGTH_SHORT).show() }
                6 -> { app.zoom = maxOf(50, app.zoom - 10); save(); if (app.zoom == 100) web.reload() else applyZoom(); Toast.makeText(this, "Zoom ${app.zoom}%", Toast.LENGTH_SHORT).show() }
                7 -> finish()
            }
        }.show()
    }

    private fun save() { if (app.id != "tmp") Apps.update(this, app) }

    private fun askAddress() {
        val et = EditText(this).apply { setSingleLine(true); setText(web.url ?: app.url); setSelectAllOnFocus(true); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
        menu = dlg().setTitle("Open address").setView(et)
            .setPositiveButton("Go") { _, _ -> load(Fetcher.normalize(et.text.toString())) }
            .setNegativeButton("Cancel", null).show()
    }
}
