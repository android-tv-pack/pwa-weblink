package app.hermes.webapps

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** What we learned about a link: everything a PWA install would take from the site. */
class Draft(val app: WebApp, val icon: Bitmap, val viaManifest: Boolean, val iconFrom: String)

/**
 * Reads a page the way Chrome does when it offers "Install app": the web app manifest (name, icons,
 * theme colour, scope), with fallbacks to the page title, theme-color meta tag, apple-touch-icon and favicon.
 */
object Fetcher {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val main = Handler(Looper.getMainLooper())

    fun normalize(input: String): String {
        var s = input.trim().replace(" ", "")
        if (s.isEmpty()) return s
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) s = "https://$s"
        return s
    }

    /** the site's name/icon is a convenience: if nothing can be read, the link is still added with a letter tile */
    fun basic(input: String): Draft {
        val u = URL(normalize(input))
        val label = u.host.removePrefix("www.").substringBeforeLast('.').replaceFirstChar { it.uppercase() }.ifBlank { u.host }
        val col = hashColor(u.host)
        val sc = u.protocol + "://" + u.host + "/"
        return Draft(WebApp(Apps.idFor(u.toString()), label, u.toString(), sc, col), letter(label, col), false, "generated")
    }

    fun discover(act: Activity, input: String, done: (Draft?, String?) -> Unit) {
        Thread {
            var err: String? = null
            var draft: Draft? = null
            try { draft = run(normalize(input)) } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                // the address does not exist / the server can't be reached: a browser engine won't do better, so stop here
                if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException) {
                    val why = if (e is java.net.UnknownHostException) "This address can't be found (check the spelling)" else "The site didn't answer"
                    main.post { done(null, why) }
                    return@Thread
                }
            }
            if (draft != null) { main.post { done(draft, null) }; return@Thread }
            // blocked (Cloudflare and similar: 403/429/503) or unreadable: a real browser engine can often get through
            main.post { viaWebView(act, normalize(input)) { d, e2 -> done(d, e2 ?: err) } }
        }.start()
    }

    private var cookieUa: String? = null
    private var bridge: Bridge? = null

    private class Bridge(val web: WebView) {
        private val results = java.util.concurrent.ConcurrentHashMap<Int, String>()
        private val latches = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CountDownLatch>()
        private var n = 0
        @JavascriptInterface fun done(id: Int, data: String) { results[id] = data; latches[id]?.countDown() }

        /** downloads through the page itself (same cookies, same browser fingerprint), blocking the calling worker thread */
        fun fetch(url: String): Resp? {
            val id = synchronized(this) { ++n }
            val l = java.util.concurrent.CountDownLatch(1); latches[id] = l
            val js = "(function(){fetch(" + JSONObject.quote(url) + ",{credentials:'include'}).then(function(r){if(!r.ok)throw 0;return r.blob()})" +
                ".then(function(b){var f=new FileReader();f.onload=function(){B.done($id,f.result)};f.readAsDataURL(b)}).catch(function(){B.done($id,'')})})()"
            main.post { web.evaluateJavascript(js, null) }
            l.await(15, java.util.concurrent.TimeUnit.SECONDS)
            val d = results.remove(id); latches.remove(id)
            if (d.isNullOrEmpty() || !d.contains(",")) return null
            val type = d.substringAfter("data:", "").substringBefore(";")
            return try { Resp(url, android.util.Base64.decode(d.substringAfter(","), android.util.Base64.DEFAULT), type) } catch (e: Exception) { null }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun viaWebView(act: Activity, url: String, done: (Draft?, String?) -> Unit) {
        val web = WebView(act)
        val holder = act.findViewById<ViewGroup>(android.R.id.content)
        val s = web.settings
        s.javaScriptEnabled = true; s.domStorageEnabled = true
        val chrome = Regex("Chrome/([0-9.]+)").find(s.userAgentString)?.groupValues?.get(1) ?: "124.0.0.0"
        val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        s.userAgentString = ua
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // it has to be attached and (barely) visible for the page's scripts and timers to run normally
        web.alpha = 0.02f; web.isFocusable = false; web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        holder.addView(web, 0, FrameLayout.LayoutParams(1280, 720))
        val br = Bridge(web)
        web.addJavascriptInterface(br, "B")
        var finished = false
        fun finish(d: Draft?, e: String?) {
            if (finished) return; finished = true
            bridge = null; cookieUa = null
            try { holder.removeView(web); web.destroy() } catch (_: Exception) {}
            done(d, e)
        }
        web.webViewClient = object : WebViewClient() {}
        web.loadUrl(url)
        val t0 = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                web.evaluateJavascript("(function(){return JSON.stringify([document.title,location.href,document.readyState,document.documentElement.outerHTML.length])})()") { r ->
                    if (finished) return@evaluateJavascript
                    val parts = try { JSONArray(JSONTokener(r).nextValue() as String) } catch (e: Exception) { null }
                    val title = parts?.optString(0).orEmpty()
                    val blocked = Regex("just a moment|attention required|checking your browser|access denied|ddos|проверка", RegexOption.IGNORE_CASE).containsMatchIn(title)
                    val ready = parts != null && parts.optString(2) == "complete" && parts.optInt(3) > 1500 && !blocked
                    val waited = System.currentTimeMillis() - t0
                    if (ready && waited > 1500) {
                        web.evaluateJavascript("document.documentElement.outerHTML") { h ->
                            val html = try { JSONTokener(h).nextValue() as String } catch (e: Exception) { "" }
                            val pageUrl = parts!!.optString(1)
                            bridge = br; cookieUa = ua
                            Thread {
                                val d = try { analyse(pageUrl, html) } catch (e: Exception) { null }
                                main.post { finish(d, if (d == null) "The page opened but had no readable info" else null) }
                            }.start()
                        }
                    } else if (waited > 35000) finish(null, if (blocked) "The site shows a human-check page" else "The site didn't load in time")
                    else main.postDelayed(this, 800)
                }
            }
        }
        main.postDelayed(poll, 1200)
    }

    private class Resp(val url: String, val bytes: ByteArray, val type: String)

    private fun get(u0: String, max: Int = 2_000_000): Resp {
        try { return getHttp(u0, max) } catch (e: Exception) {
            val b = bridge ?: throw e
            return b.fetch(u0) ?: throw e
        }
    }

    private fun getHttp(u0: String, max: Int): Resp {
        var u = u0
        for (hop in 0..6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", cookieUa ?: UA)
            c.setRequestProperty("Accept-Language", "en")
            if (cookieUa != null) CookieManager.getInstance().getCookie(u)?.let { c.setRequestProperty("Cookie", it) }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw RuntimeException("Redirect without a target")
                u = URL(URL(u), loc).toString(); c.disconnect(); continue
            }
            if (code >= 400) throw RuntimeException("The site answered HTTP $code")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins -> val buf = ByteArray(16384); var n: Int; while (ins.read(buf).also { n = it } > 0 && out.size() < max) out.write(buf, 0, n) }
            return Resp(u, out.toByteArray(), c.contentType ?: "")
        }
        throw RuntimeException("Too many redirects")
    }

    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return (m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value)?.trim()
    }

    private fun resolve(base: String, rel: String): String? = try { URL(URL(base), rel.replace("&amp;", "&")).toString() } catch (e: Exception) { null }

    private fun sizeOf(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        return s.split(" ").mapNotNull { Regex("(\\d+)x(\\d+)").find(it)?.let { m -> maxOf(m.groupValues[1].toInt(), m.groupValues[2].toInt()) } }.maxOrNull() ?: 0
    }

    private fun color(s: String?): Int? = try { if (s.isNullOrBlank()) null else Color.parseColor(s.trim()) or 0xFF000000.toInt() } catch (e: Exception) { null }

    private class Cand(val url: String, val score: Int, val tag: String)

    private fun decode(r: Resp): String {
        val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(r.type)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(String(r.bytes, 0, minOf(r.bytes.size, 3000), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return try { String(r.bytes, java.nio.charset.Charset.forName(cs ?: "UTF-8")) } catch (e: Exception) { String(r.bytes, Charsets.UTF_8) }
    }

    private fun run(start: String): Draft {
        val page = get(start)
        return analyse(page.url, decode(page))
    }

    private fun analyse(pageUrl: String, html: String): Draft {
        val page = Resp(pageUrl, ByteArray(0), "")
        val head = html.take(400_000)
        val tags = Regex("<(?:link|meta)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(head).map { it.value }.toList()

        var manifestUrl: String? = null
        var theme: Int? = null
        var siteName: String? = null
        val cands = ArrayList<Cand>()
        for (t in tags) {
            val rel = attr(t, "rel")?.lowercase(Locale.ROOT)
            if (t.startsWith("<link", true) && rel != null) {
                val href = attr(t, "href") ?: continue
                if (rel.split(" ").contains("manifest")) manifestUrl = resolve(page.url, href)
                else if (rel.contains("icon")) {
                    val sz = sizeOf(attr(t, "sizes")).let { if (it > 0) it else if (rel.contains("apple")) 180 else 32 }
                    resolve(page.url, href)?.let { if (!it.substringBefore('?').endsWith(".svg", true)) cands += Cand(it, score(sz) + (if (rel.contains("apple")) 20 else 0), "html icon") }
                }
            } else if (t.startsWith("<meta", true)) {
                when (attr(t, "name")?.lowercase(Locale.ROOT) ?: attr(t, "property")?.lowercase(Locale.ROOT)) {
                    "theme-color" -> if (theme == null) theme = color(attr(t, "content"))
                    "application-name", "og:site_name", "apple-mobile-web-app-title" -> if (siteName == null) siteName = attr(t, "content")
                }
            }
        }
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(head)?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")?.replace("&amp;", "&")?.trim()

        var name: String? = null
        var scope: String? = null
        var bg: Int? = null
        var viaManifest = false
        if (manifestUrl != null) try {
            val mr = get(manifestUrl, 400_000)
            val m = JSONObject(String(mr.bytes, Charsets.UTF_8))
            viaManifest = true
            name = m.optString("short_name").ifBlank { m.optString("name") }.ifBlank { null }
            m.optString("scope").takeIf { it.isNotBlank() }?.let { scope = resolve(mr.url, it) }
            color(m.optString("theme_color"))?.let { theme = it }
            bg = color(m.optString("background_color"))
            val arr = m.optJSONArray("icons")
            if (arr != null) for (i in 0 until arr.length()) {
                val ic = arr.getJSONObject(i)
                val src = ic.optString("src").ifBlank { null } ?: continue
                val type = ic.optString("type")
                if (type.contains("svg") || src.substringBefore('?').endsWith(".svg", true)) continue
                val sz = sizeOf(ic.optString("sizes")).let { if (it > 0) it else 256 }
                val purpose = ic.optString("purpose", "any")
                resolve(mr.url, src)?.let { cands += Cand(it, score(sz) + 40 - (if (purpose.contains("maskable") && !purpose.contains("any")) 25 else 0), "manifest icon") }
            }
        } catch (e: Exception) { /* a broken manifest is not fatal */ }

        val u = URL(page.url)
        resolve(page.url, "/favicon.ico")?.let { cands += Cand(it, 5, "favicon") }
        var icon: Bitmap? = null
        var from = "generated"
        for (c in cands.sortedByDescending { it.score }.take(8)) {
            try {
                val r = get(c.url, 1_500_000)
                val b = BitmapFactory.decodeByteArray(r.bytes, 0, r.bytes.size) ?: continue
                if (b.width >= 32) { icon = b; from = c.tag; break }
            } catch (e: Exception) { }
        }

        val label = (name ?: siteName ?: title?.split(" - ", " | ", " – ", " — ")?.firstOrNull() ?: u.host.removePrefix("www."))
            .trim().take(40).ifBlank { u.host }
        val col = theme ?: bg ?: icon?.let { average(it) } ?: hashColor(u.host)
        val finalIcon = icon?.let { square(it, 256) } ?: letter(label, col)
        val sc = scope ?: (u.protocol + "://" + u.host + (if (u.port > 0) ":${u.port}" else "") + "/")
        val app = WebApp(Apps.idFor(page.url), label, page.url, sc, col)
        return Draft(app, finalIcon, viaManifest, from)
    }

    private fun score(sz: Int) = if (sz in 128..512) 100 - Math.abs(sz - 256) / 8 else if (sz > 512) 60 else sz / 3

    private fun hashColor(s: String): Int {
        val h = (Math.abs(s.hashCode()) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(h, 0.55f, 0.55f))
    }

    private fun average(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = s.getPixel(x, y)
            if (Color.alpha(p) < 160) continue
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        return if (n == 0) 0xFF203050.toInt() else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    /** icon fitted into a square, keeping transparency */
    private fun square(b: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val k = minOf(size.toFloat() / b.width, size.toFloat() / b.height)
        val w = b.width * k; val h = b.height * k
        c.drawBitmap(b, null, android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun letter(name: String, col: Int): Bitmap {
        val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = col
        c.drawRoundRect(0f, 0f, 256f, 256f, 56f, 56f, p)
        p.color = Color.WHITE; p.textSize = 150f; p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); p.textAlign = Paint.Align.CENTER
        c.drawText(name.firstOrNull()?.uppercase() ?: "W", 128f, 128f + 52f, p)
        return out
    }
}
