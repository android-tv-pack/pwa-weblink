package app.hermes.webapps

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/** A tiny web page on the TV's address, so a link can be pasted from a phone or computer instead of typed with a remote. */
class PhoneServer(private val port: Int, private val names: () -> List<String>, private val onUrl: (String) -> String) {
    private var ss: ServerSocket? = null

    fun start() {
        if (ss != null) return
        Thread {
            try {
                val s = ServerSocket(port); ss = s
                while (!s.isClosed) { val c = s.accept(); Thread { handle(c) }.start() }
            } catch (_: Exception) { }
        }.apply { isDaemon = true }.start()
    }

    fun stop() { try { ss?.close() } catch (_: Exception) {}; ss = null }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 5000
            val line = BufferedReader(InputStreamReader(c.getInputStream())).readLine() ?: return
            val path = line.split(" ").getOrNull(1) ?: "/"
            var msg = ""
            if (path.startsWith("/add")) {
                val u = path.substringAfter("url=", "").substringBefore('&')
                if (u.isNotBlank()) msg = onUrl(URLDecoder.decode(u, "UTF-8"))
            }
            val list = names().joinToString("") { "<li>${it.replace("<", "&lt;")}</li>" }
            val html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><meta charset=utf-8><title>Web Apps</title>" +
                "<style>body{font-family:-apple-system,sans-serif;background:#0b1020;color:#fff;max-width:560px;margin:0 auto;padding:28px}" +
                "input{width:100%;box-sizing:border-box;font-size:18px;padding:14px;border-radius:12px;border:0;margin:8px 0}" +
                "button{font-size:18px;padding:14px 22px;border:0;border-radius:12px;background:#4dd0e1;color:#06202a;font-weight:600}" +
                ".m{background:#1b2a4a;border-radius:12px;padding:12px;margin:12px 0}li{margin:4px 0}h1{font-weight:300}</style></head><body>" +
                "<h1>Web Apps on your TV</h1><form action=/add><input name=url placeholder='https://… or example.com' autofocus><button>Add to the TV</button></form>" +
                (if (msg.isNotEmpty()) "<div class=m>${msg.replace("<", "&lt;")}</div>" else "") +
                "<h3>On the TV now</h3><ul>$list</ul></body></html>"
            val b = html.toByteArray()
            val o = c.getOutputStream()
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
            o.write(b); o.flush()
        } catch (_: Exception) { } finally { try { c.close() } catch (_: Exception) {} }
    }
}

class MainActivity : Activity() {
    private lateinit var grid: GridLayout
    private lateinit var addr: TextView
    private var server: PhoneServer? = null
    private val d get() = resources.displayMetrics.density
    private fun dp(n: Int) = (n * d).toInt()
    private val cols = 5

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(11, 16, 38), Color.rgb(5, 7, 18)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(44), dp(28), dp(44), dp(16)) }
        col.addView(TextView(this).apply { text = "Web Apps"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f); setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        col.addView(TextView(this).apply { text = "Any website, as an app on your TV. Add a link and it gets its own tile."; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Color.argb(170, 255, 255, 255)); setPadding(0, dp(2), 0, dp(14)) })
        grid = GridLayout(this).apply { columnCount = cols; clipChildren = false; clipToPadding = false }
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; setPadding(0, dp(8), 0, dp(8)) }
        sv.addView(grid)
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        addr = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.argb(150, 255, 255, 255)); setPadding(0, dp(8), 0, 0) }
        col.addView(addr)
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        handle(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handle(i) }

    private fun handle(i: Intent) {
        i.getStringExtra("add_url")?.let { u -> i.removeExtra("add_url"); addUrl(u, auto = true) }
    }

    override fun onStart() {
        super.onStart()
        refresh()
        server = PhoneServer(8765, { Apps.all(this).map { it.name } }) { u ->
            val msg = arrayOfNulls<String>(1)
            val latch = java.util.concurrent.CountDownLatch(1)
            runOnUiThread { addUrl(u, auto = true) { m -> msg[0] = m; latch.countDown() } }
            latch.await(25, java.util.concurrent.TimeUnit.SECONDS)
            msg[0] ?: "Still working on it, check the TV"
        }.also { it.start() }
        val ip = localIp()
        addr.text = if (ip != null) "Paste links from your phone or computer:  http://$ip:8765      ·      Hold OK on a tile for options" else "Hold OK on a tile for options"
    }

    override fun onStop() { super.onStop(); server?.stop(); server = null }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ tiles

    private fun refresh(focusId: String? = null) {
        grid.removeAllViews()
        val apps = Apps.all(this)
        var first: View? = null
        var target: View? = null
        for (a in apps) {
            val v = tile(Apps.icon(this, a.id), a.name, a.color)
            v.setOnClickListener { open(a) }
            v.setOnLongClickListener { options(a); true }
            grid.addView(v, cell())
            if (first == null) first = v
            if (a.id == focusId) target = v
        }
        val add = tile(null, "Add a web app", Color.rgb(32, 44, 80), plus = true)
        add.setOnClickListener { askUrl() }
        grid.addView(add, cell())
        val want = target ?: first ?: add
        want.requestFocus()
        // closing the "reading the site" dialog hands focus back to whatever had it before: claim it again afterwards
        grid.postDelayed({ if (want.isAttachedToWindow && !want.hasFocus()) want.requestFocus() }, 350)
    }

    private fun cell() = GridLayout.LayoutParams().apply { width = dp(168); height = dp(140); setMargins(dp(6), dp(6), dp(6), dp(6)) }

    private fun tile(icon: Bitmap?, name: String, color: Int, plus: Boolean = false): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true; isLongClickable = true; setPadding(dp(8), dp(10), dp(8), dp(8)) }
        val dark = Color.rgb((Color.red(color) * 0.42).toInt(), (Color.green(color) * 0.42).toInt(), (Color.blue(color) * 0.42).toInt())
        val normal = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(dark); setStroke(dp(1), Color.argb(50, 255, 255, 255)) }
        val focus = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(dark); setStroke(dp(3), Color.WHITE) }
        box.background = normal
        box.setOnFocusChangeListener { v, has ->
            v.background = if (has) focus else normal
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
            v.elevation = if (has) dp(8).toFloat() else 0f
        }
        if (plus) box.addView(TextView(this).apply { text = "+"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f); setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }, LinearLayout.LayoutParams(-2, dp(68)))
        else {
            val iv = ImageView(this); iv.setImageBitmap(icon ?: Fetcher.letter(name, color)); iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.22f) } }
            iv.clipToOutline = true
            box.addView(iv, LinearLayout.LayoutParams(dp(68), dp(68)))
        }
        box.addView(TextView(this).apply { text = name; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.WHITE); setPadding(0, dp(8), 0, 0) })
        return box
    }

    private fun open(a: WebApp) {
        startActivity(Intent(this, WebActivity::class.java).putExtra("id", a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun options(a: WebApp) {
        val items = arrayOf("Open", "Rename…", "Pointer: " + (if (a.cursor) "On" else "Off"), "Layout: " + (if (a.desktop) "Desktop" else "Mobile"), "Refresh name and icon", "Remove")
        dlg().setTitle(a.name).setItems(items) { _, i ->
            when (i) {
                0 -> open(a)
                1 -> rename(a)
                2 -> { a.cursor = !a.cursor; Apps.update(this, a); refresh(a.id) }
                3 -> { a.desktop = !a.desktop; Apps.update(this, a); refresh(a.id) }
                4 -> { addUrl(a.url, auto = true, keepId = a.id) }
                5 -> dlg().setTitle("Remove ${a.name}?").setPositiveButton("Remove") { _, _ -> Apps.remove(this, a.id); refresh() }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun rename(a: WebApp) {
        val et = EditText(this).apply { setSingleLine(true); setText(a.name); setSelectAllOnFocus(true) }
        dlg().setTitle("Name").setView(et).setPositiveButton("Save") { _, _ -> if (et.text.isNotBlank()) { a.name = et.text.toString().trim(); Apps.update(this, a); refresh(a.id) } }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ adding

    private fun askUrl() {
        val et = EditText(this).apply {
            hint = "example.com or paste a link"; setSingleLine(true); inputType = InputType.TYPE_TEXT_VARIATION_URI; imeOptions = EditorInfo.IME_ACTION_GO
        }
        val dialog = dlg().setTitle("Add a web app").setView(et)
            .setPositiveButton("Add") { _, _ -> addUrl(et.text.toString(), auto = false) }
            .setNegativeButton("Cancel", null).create()
        et.setOnEditorActionListener { _, _, _ -> dialog.dismiss(); addUrl(et.text.toString(), auto = false); true }
        dialog.show()
    }

    /** auto = save straight away (phone page, adb, refresh); otherwise show what was found first */
    private fun addUrl(input: String, auto: Boolean, keepId: String? = null, done: ((String) -> Unit)? = null) {
        if (input.isBlank()) { done?.invoke("Nothing to add"); return }
        val wait = dlg().setTitle("Reading the site…").setMessage(Fetcher.normalize(input) + "\n\nSome sites make the page prove it is a browser first, this can take up to half a minute.").setCancelable(true).show()
        Fetcher.discover(this, input) { draft, err ->
            wait.dismiss()
            val old = keepId?.let { Apps.get(this, it) }
            fun save(d: Draft, note: String) {
                if (old != null) { d.app.cursor = old.cursor; d.app.desktop = old.desktop; d.app.zoom = old.zoom }
                val fa = if (keepId != null) WebApp(keepId, d.app.name, old?.url ?: d.app.url, d.app.scope, d.app.color, d.app.cursor, d.app.desktop, d.app.zoom) else d.app
                Apps.add(this, fa, d.icon); refresh(fa.id)
                val m = "Added ${fa.name} ($note)"
                Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); done?.invoke(m)
            }
            if (draft == null) {
                // the site's info is a nicety: the link itself still works as a web app
                val fallback = try { Fetcher.basic(input) } catch (e: Exception) { null }
                if (fallback == null) { val m = "That doesn't look like a web address"; Toast.makeText(this, m, Toast.LENGTH_LONG).show(); done?.invoke(m); return@discover }
                if (auto) { save(fallback, "couldn't read its info: ${err ?: "unknown"}; letter icon"); return@discover }
                dlg().setTitle("Couldn't read this site's info").setMessage((err ?: "Unknown error") + "\n\nAdd it anyway? It gets the site's name and a letter icon, and still opens as a full-screen app.")
                    .setPositiveButton("Add anyway") { _, _ -> preview(fallback, fallback.app) }
                    .setNegativeButton("Cancel", null).show()
                done?.invoke("Couldn't read the site's info: ${err ?: "unknown"}")
                return@discover
            }
            if (auto) save(draft, (if (draft.viaManifest) "web app manifest" else "page info") + ", icon: ${draft.iconFrom}")
            else {
                if (old != null) { draft.app.cursor = old.cursor; draft.app.desktop = old.desktop; draft.app.zoom = old.zoom }
                preview(draft, if (keepId != null) WebApp(keepId, draft.app.name, old?.url ?: draft.app.url, draft.app.scope, draft.app.color, draft.app.cursor, draft.app.desktop, draft.app.zoom) else draft.app)
                done?.invoke("ok")
            }
        }
    }

    private fun preview(draft: Draft, app: WebApp) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val iv = ImageView(this); iv.setImageBitmap(draft.icon)
        col.addView(iv, LinearLayout.LayoutParams(dp(84), dp(84)))
        val et = EditText(this).apply { setSingleLine(true); setText(app.name); setSelectAllOnFocus(true); gravity = Gravity.CENTER }
        col.addView(et, LinearLayout.LayoutParams(-1, -2))
        col.addView(TextView(this).apply {
            text = (if (draft.viaManifest) "Full web app manifest found" else "No manifest: using the page's own info") + "  ·  icon: ${draft.iconFrom}\n" + app.url
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Color.argb(170, 255, 255, 255)); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        dlg().setTitle("Add to Web Apps").setView(col)
            .setPositiveButton("Add") { _, _ ->
                if (et.text.isNotBlank()) app.name = et.text.toString().trim()
                Apps.add(this, app, draft.icon); refresh(app.id)
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO) { askUrl(); return true }
        return super.onKeyDown(k, e)
    }
}
