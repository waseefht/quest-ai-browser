package com.waseef.questaibrowser

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    @Test fun tenDistinctEngines() {
        assertEquals(10, Engines.ALL.size)
        assertEquals(10, Engines.ALL.map { it.id }.toSet().size)
        assertTrue(Engines.ALL.count { it.ai } >= 3)
        Engines.ALL.forEach { assertTrue(it.template.startsWith("https://") && it.template.contains("%s")) }
    }

    @Test fun safeSearchOffParams() {
        assertTrue(Engines.searchUrl(Engines.byId("bing")!!, "x").contains("adlt=off"))
        assertTrue(Engines.searchUrl(Engines.byId("ddg")!!, "x").contains("kp=-2"))
        assertTrue(Engines.searchUrl(Engines.byId("yandex")!!, "x").contains("family=no"))
        assertTrue(Engines.searchUrl(Engines.byId("brave")!!, "x").contains("safesearch=off"))
        assertTrue(Engines.searchUrl(Engines.byId("google")!!, "x").contains("safe=off"))
    }

    @Test fun encodesArabicQuery() {
        val u = Engines.searchUrl(Engines.byId("google")!!, "قطط مضحكة")
        assertTrue(u.startsWith("https://www.google.com/search?q=%D9%82"))
        assertFalse(u.contains(" "))
    }

    @Test fun extractsQueryFromResultPages() {
        assertEquals("cats & dogs", Engines.extractQuery("https://www.bing.com/search?q=cats+%26+dogs&adlt=off"))
        assertEquals("قطط", Engines.extractQuery("https://yandex.com/search/?text=%D9%82%D8%B7%D8%B7&family=no"))
        assertEquals("x y", Engines.extractQuery(Engines.searchUrl(Engines.byId("marginalia")!!, "x y")))
        Engines.ALL.forEach { assertEquals("hello world", Engines.extractQuery(Engines.searchUrl(it, "hello world"))) }
        assertNull(Engines.extractQuery("https://example.com/?q=zzz"))
        assertNull(Engines.extractQuery("https://www.google.com/"))
        assertNull(Engines.extractQuery(null))
    }

    @Test fun addressBarInput() {
        assertEquals("https://example.com", Engines.toUrl("example.com"))
        assertEquals("http://a.b/c", Engines.toUrl("http://a.b/c"))
        assertTrue(Engines.toUrl("how to fly").contains("google.com/search?q=how+to+fly"))
        assertEquals("https://sub.site.co.uk/path?x=1", Engines.toUrl("sub.site.co.uk/path?x=1"))
    }

    @Test fun arabicDetection() {
        assertTrue(TextUtil.isMostlyArabic("مرحبا بالعالم"))
        assertFalse(TextUtil.isMostlyArabic("Hello world"))
        assertTrue(TextUtil.isMostlyArabic("123 !!"))
    }

    @Test fun chunking() {
        val long = (1..60).joinToString(" ") { "Sentence number $it is here." }
        val c = TextUtil.chunk(long, 180)
        assertTrue(c.size > 5)
        c.forEach { assertTrue(it.length <= 180) }
        assertEquals(long.replace(" ", ""), c.joinToString("").replace(" ", ""))
        val noSpace = "a".repeat(500)
        TextUtil.chunk(noSpace, 180).forEach { assertTrue(it.length <= 180) }
        assertTrue(TextUtil.chunk("   ").isEmpty())
    }

    @Test fun userAgents() {
        val d = "Mozilla/5.0 (Linux; Android 12; Quest 2 Build/SQ3A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.146 Mobile Safari/537.36"
        val desk = TextUtil.desktopUa(d)
        assertTrue(desk.contains("Windows NT 10.0") && desk.contains("Chrome/128.0.6613.146") && desk.contains("Edg/128"))
        val m = TextUtil.mobileUa(d)
        assertFalse(m.contains("; wv")); assertFalse(m.contains("Version/4.0"))
    }

    @Test fun parsesTranslateResponses() {
        assertEquals(listOf("مرحبا بالعالم", "القطة"), Translator.parse("""[["مرحبا بالعالم","en"],["القطة","en"]]"""))
        assertEquals(listOf("أ", "ب"), Translator.parse("""["أ","ب"]"""))
        assertEquals(listOf("واحد"), Translator.parse("\"واحد\""))
    }
}
