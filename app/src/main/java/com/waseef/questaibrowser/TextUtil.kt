package com.waseef.questaibrowser

object TextUtil {
    private val arabic = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    private val letter = Regex("\\p{L}")

    /** True when most letters in [s] are Arabic (so no translation is needed). */
    fun isMostlyArabic(s: String): Boolean {
        val letters = letter.findAll(s).count()
        if (letters == 0) return true
        return arabic.findAll(s).count() * 1.0 / letters > 0.6
    }

    /** Splits long text into speakable chunks at sentence boundaries, each <= [max] chars. */
    fun chunk(text: String, max: Int = 180): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = clean.split(Regex("(?<=[.!?؟،,;:\\n])\\s+"))
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotBlank()) out.add(sb.toString().trim()); sb.setLength(0) }
        for (s in sentences) {
            if (s.length > max) {
                flush()
                var rest = s
                while (rest.length > max) {
                    var cut = rest.lastIndexOf(' ', max)
                    if (cut < max / 2) cut = max
                    out.add(rest.substring(0, cut).trim()); rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) sb.append(rest).append(' ')
            } else {
                if (sb.length + s.length + 1 > max) flush()
                sb.append(s).append(' ')
            }
        }
        flush()
        return out
    }

    /** Edge/Chrome-like desktop UA built from the WebView's real Chromium version. */
    fun desktopUa(defaultUa: String): String {
        val ver = Regex("Chrome/([\\d.]+)").find(defaultUa)?.groupValues?.get(1) ?: "130.0.0.0"
        val major = ver.substringBefore('.')
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36 Edg/$major.0.0.0"
    }

    /** Mobile UA without the WebView markers ("; wv", "Version/4.0") so sites serve the normal Chrome experience. */
    fun mobileUa(defaultUa: String): String =
        defaultUa.replace("; wv", "").replace(Regex("Version/[\\d.]+ "), "")
}
