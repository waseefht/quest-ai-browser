package com.waseef.questaibrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny persistent store: settings, bookmarks, history (SharedPreferences + JSON). */
class Store(ctx: Context) {
    val p = ctx.getSharedPreferences("qab", Context.MODE_PRIVATE)

    var desktop: Boolean get() = p.getBoolean("desktop", false); set(v) = p.edit().putBoolean("desktop", v).apply()
    var dupOnTap: Boolean get() = p.getBoolean("dupOnTap", true); set(v) = p.edit().putBoolean("dupOnTap", v).apply()
    var autoTranslate: Boolean get() = p.getBoolean("autoTr", false); set(v) = p.edit().putBoolean("autoTr", v).apply()
    var voice: String get() = p.getString("voice", "") ?: ""; set(v) = p.edit().putString("voice", v).apply()
    var rate: Float get() = p.getFloat("rate", 1.0f); set(v) = p.edit().putFloat("rate", v).apply()
    var pitch: Float get() = p.getFloat("pitch", 1.1f); set(v) = p.edit().putFloat("pitch", v).apply()
    var duck: Float get() = p.getFloat("duck", 0.25f); set(v) = p.edit().putFloat("duck", v).apply()
    var gain: Int get() = p.getInt("gain", 100); set(v) = p.edit().putInt("gain", v).apply()
    var preset: String get() = p.getString("preset", "flat") ?: "flat"; set(v) = p.edit().putString("preset", v).apply()
    var normalize: Boolean get() = p.getBoolean("norm", false); set(v) = p.edit().putBoolean("norm", v).apply()
    var home: String get() = p.getString("home", HOME) ?: HOME; set(v) = p.edit().putString("home", v).apply()
    var maxLive: Int get() = p.getInt("maxLive", 3); set(v) = p.edit().putInt("maxLive", v).apply()

    data class Item(val title: String, val url: String, val time: Long)

    private fun list(key: String): MutableList<Item> {
        val a = try { JSONArray(p.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.optString("t"), o.optString("u"), o.optLong("d")) }.toMutableList()
    }
    private fun save(key: String, l: List<Item>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        p.edit().putString(key, a.toString()).apply()
    }

    fun bookmarks() = list("bm")
    fun isBookmarked(url: String) = bookmarks().any { it.url == url }
    fun toggleBookmark(title: String, url: String): Boolean {
        val l = bookmarks()
        val had = l.removeAll { it.url == url }
        if (!had) l.add(0, Item(title.ifBlank { url }, url, System.currentTimeMillis()))
        save("bm", l); return !had
    }
    fun removeBookmark(url: String) { save("bm", bookmarks().filter { it.url != url }) }

    fun history() = list("hist")
    fun addHistory(title: String, url: String) {
        if (url.startsWith("file:") || url.startsWith("about:") || url.startsWith("data:")) return
        val l = history(); l.removeAll { it.url == url }
        l.add(0, Item(title.ifBlank { url }, url, System.currentTimeMillis()))
        save("hist", l.take(300))
    }
    fun clearHistory() = save("hist", emptyList())

    companion object { const val HOME = "file:///android_asset/home.html" }
}
