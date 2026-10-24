package com.waseef.questaibrowser

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Free machine translation (Google Translate public "gtx" endpoint, no API key). Call off the main thread. */
object Translator {
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 2000
    }

    /** Translates many strings in one request; result has the same size (falls back to the source text). */
    fun translateBatch(items: List<String>, target: String = "ar"): List<String> {
        val out = MutableList(items.size) { items[it] }
        val todo = ArrayList<Int>()
        synchronized(cache) {
            items.forEachIndexed { i, s ->
                val c = cache["$target|$s"]
                if (c != null) out[i] = c else if (s.isNotBlank() && !(target == "ar" && TextUtil.isMostlyArabic(s))) todo.add(i)
            }
        }
        if (todo.isEmpty()) return out
        val body = todo.joinToString("&") { "q=" + URLEncoder.encode(items[it], "UTF-8") }
        val c = URL("https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=$target&dj=1")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.outputStream.use { it.write(body.toByteArray()) }
            val txt = c.inputStream.bufferedReader().readText()
            val arr = parse(txt)
            todo.forEachIndexed { k, i ->
                val t = arr.getOrNull(k)
                if (!t.isNullOrBlank()) { out[i] = t; synchronized(cache) { cache["$target|${items[i]}"] = t } }
            }
        } finally { c.disconnect() }
        return out
    }

    fun translate(s: String, target: String = "ar"): String = translateBatch(listOf(s), target)[0]

    /** Response is either ["a","b"] or [["a","en"],["b","en"]] (or a bare string for a single q). */
    fun parse(txt: String): List<String> {
        val t = txt.trim()
        if (t.startsWith("\"")) return listOf(JSONArray("[$t]").getString(0))
        val a = JSONArray(t)
        return (0 until a.length()).map { i ->
            when (val v = a.get(i)) {
                is JSONArray -> v.optString(0)
                else -> v.toString()
            }
        }
    }
}
