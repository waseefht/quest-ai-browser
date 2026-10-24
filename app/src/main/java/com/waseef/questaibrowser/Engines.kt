package com.waseef.questaibrowser

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** A search engine tile in the top bar. [template] contains %s for the URL-encoded query. */
data class Engine(
    val id: String,
    val label: String,
    val color: Int,
    val template: String,
    val hosts: List<String>,
    val queryParams: List<String>,
    val ai: Boolean = false,
    val why: String = "",
)

object Engines {
    /** Ten engines with different indexes / AI back-ends so results overlap as little as possible.
     *  SafeSearch is switched off through URL parameters where the engine supports it. */
    val ALL = listOf(
        Engine("yandex", "ياندكس", 0xFFFC3F1D.toInt(), "https://yandex.com/search/?text=%s&family=no",
            listOf("yandex."), listOf("text"), why = "فهرس روسي/عالمي مستقل وأقل تقييدًا"),
        Engine("google", "جوجل", 0xFF4285F4.toInt(), "https://www.google.com/search?q=%s&safe=off&filter=0",
            listOf("google."), listOf("q"), why = "أكبر فهرس للويب، مع إيقاف التصفية"),
        Engine("bing", "بينج", 0xFF00809D.toInt(), "https://www.bing.com/search?q=%s&adlt=off",
            listOf("bing.com"), listOf("q"), why = "فهرس مايكروسوفت المستقل"),
        Engine("brave", "بريف", 0xFFFB542B.toInt(), "https://search.brave.com/search?q=%s&safesearch=off&source=web",
            listOf("search.brave.com"), listOf("q"), why = "فهرس مستقل خاص ببريف + ملخص ذكي"),
        Engine("ddg", "داك داك جو", 0xFFDE5833.toInt(), "https://duckduckgo.com/?q=%s&kp=-2&kl=wt-wt",
            listOf("duckduckgo.com"), listOf("q"), why = "خصوصية + اختصارات !bang لمئات المواقع"),
        Engine("mojeek", "موجيك", 0xFF7AB93C.toInt(), "https://www.mojeek.com/search?q=%s&safe=0",
            listOf("mojeek.com"), listOf("q"), why = "زاحف بريطاني بفهرس خاص بالكامل"),
        Engine("marginalia", "مارجيناليا", 0xFF8E6E53.toInt(), "https://marginalia-search.com/search?query=%s",
            listOf("marginalia-search.com", "marginalia.nu"), listOf("query"), why = "الويب المستقل والمواقع الصغيرة غير التجارية"),
        Engine("perplexity", "بيربلكسيتي AI", 0xFF20B8CD.toInt(), "https://www.perplexity.ai/search?q=%s",
            listOf("perplexity.ai"), listOf("q"), ai = true, why = "بحث ذكي بإجابات مع مصادر (قد يطلب تسجيل دخول مجاني)"),
        Engine("andi", "آندي AI", 0xFF7C4DFF.toInt(), "https://andisearch.com/?q=%s",
            listOf("andisearch.com"), listOf("q"), ai = true, why = "بحث ذكي مجاني بدون حساب وبدون إعلانات"),
        Engine("googleai", "جوجل AI", 0xFF34A853.toInt(), "https://www.google.com/search?q=%s&udm=50&safe=off",
            listOf("google."), listOf("q"), ai = true, why = "وضع الذكاء الاصطناعي من جوجل (Gemini) بدون حساب"),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    fun searchUrl(e: Engine, query: String): String =
        e.template.replace("%s", URLEncoder.encode(query.trim(), "UTF-8"))

    /** If [url] is a results page of any known engine, returns the query typed there. */
    fun extractQuery(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = try { URI(url) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val engine = ALL.firstOrNull { e -> e.hosts.any { host.contains(it) } } ?: return null
        val params = parseQuery(uri.rawQuery)
        for (p in engine.queryParams + listOf("q", "query", "text")) {
            val v = params[p]
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = if (i < 0) part else part.substring(0, i)
            val v = if (i < 0) "" else part.substring(i + 1)
            try { out.putIfAbsent(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8")) } catch (_: Exception) {}
        }
        return out
    }

    /** Turns address-bar input into a URL: keeps URLs, adds https:// to domains, otherwise searches. */
    fun toUrl(input: String, fallback: Engine = ALL[1]): String {
        val t = input.trim()
        if (t.isEmpty()) return "about:blank"
        if (Regex("^(https?|file|about|data|javascript|intent|content):", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        if (!t.contains(' ') && (Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(t) || t.startsWith("localhost"))) return "https://$t"
        return searchUrl(fallback, t)
    }
}
