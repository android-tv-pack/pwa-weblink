package app.hermes.webapps

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved web app (what a PWA install would give you on a phone). */
class WebApp(
    val id: String,
    var name: String,
    var url: String,          // where it opens
    var scope: String,        // pages under this stay inside the app
    var color: Int,           // theme colour: splash + tile
    var cursor: Boolean = true,    // D-pad moves a mouse pointer
    var desktop: Boolean = true,   // desktop layout (false = mobile layout)
    var zoom: Int = 100
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("url", url).put("scope", scope)
        .put("color", color).put("cursor", cursor).put("desktop", desktop).put("zoom", zoom)

    companion object {
        fun from(o: JSONObject) = WebApp(
            o.getString("id"), o.getString("name"), o.getString("url"), o.optString("scope", ""),
            o.optInt("color", 0xFF203050.toInt()), o.optBoolean("cursor", true), o.optBoolean("desktop", true), o.optInt("zoom", 100)
        )
    }
}

object Apps {
    private fun p(c: Context) = c.getSharedPreferences("webapps", Context.MODE_PRIVATE)

    fun all(c: Context): List<WebApp> {
        val s = p(c).getString("list", "[]") ?: "[]"
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { WebApp.from(a.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(c: Context, l: List<WebApp>) {
        val a = JSONArray(); l.forEach { a.put(it.toJson()) }
        p(c).edit().putString("list", a.toString()).apply()
    }

    fun get(c: Context, id: String?) = all(c).firstOrNull { it.id == id }

    fun add(c: Context, app: WebApp, icon: Bitmap?) {
        icon?.let { b ->
            val f = iconFile(c, app.id); f.parentFile?.mkdirs()
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        save(c, all(c).filter { it.id != app.id } + app)
    }

    fun update(c: Context, app: WebApp) = save(c, all(c).map { if (it.id == app.id) app else it })

    fun remove(c: Context, id: String) {
        iconFile(c, id).delete()
        save(c, all(c).filter { it.id != id })
    }

    fun iconFile(c: Context, id: String) = File(File(c.filesDir, "icons"), "$id.png")
    fun icon(c: Context, id: String): Bitmap? = iconFile(c, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun idFor(url: String): String {
        val u = try { java.net.URL(url) } catch (e: Exception) { return Integer.toHexString(url.hashCode()) }
        return Integer.toHexString((u.host + u.path.trimEnd('/')).hashCode()) + Integer.toHexString(u.host.hashCode()).take(3)
    }
}

/** Lets Aurora (or any launcher) list the web apps and read their icons. Read-only. */
class WebAppsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val c = MatrixCursor(arrayOf("id", "name", "url", "color"))
        for (a in Apps.all(context!!)) c.addRow(arrayOf(a.id, a.name, a.url, a.color))
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val id = uri.lastPathSegment ?: return null
        val f = Apps.iconFile(context!!, id)
        if (!f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
