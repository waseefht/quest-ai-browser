package com.waseef.questaibrowser

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.net.URLEncoder
import java.util.Locale

/**
 * Arabic speech output for "read aloud" and live caption dubbing.
 *  - Offline: every Arabic voice of every installed Android TTS engine (female voices listed first).
 *  - Online fallback (no key): Google Translate TTS female Arabic voice, streamed in short chunks.
 */
class Speaker(ctx: Context, private val store: Store, private val onSpeaking: (Boolean) -> Unit) {
    data class Opt(val key: String, val label: String)

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var tts: TextToSpeech? = null
    @Volatile var ttsReady = false; private set
    var arVoices: List<Voice> = emptyList(); private set
    var engines: List<String> = emptyList(); private set
    private var seq = 0

    // online queue
    private var player: MediaPlayer? = null
    private val queue = ArrayDeque<String>()

    init { initTts(null) }

    fun initTts(engine: String?) {
        try { tts?.shutdown() } catch (_: Exception) {}
        ttsReady = false
        tts = TextToSpeech(app, { st ->
            if (st == TextToSpeech.SUCCESS) main.post { onTtsReady() }
        }, engine)
    }

    private fun onTtsReady() {
        val t = tts ?: return
        t.setAudioAttributes(attrs)
        engines = try { t.engines.map { it.name } } catch (_: Exception) { emptyList() }
        arVoices = try {
            t.voices.orEmpty().filter { it.locale.language == "ar" || it.locale.language == "ara" }
                .sortedWith(compareByDescending<Voice> { isFemale(it) }.thenByDescending { it.locale.country == "EG" }
                    .thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
        } catch (_: Exception) { emptyList() }
        ttsReady = arVoices.isNotEmpty() || t.isLanguageAvailable(Locale("ar")) >= TextToSpeech.LANG_AVAILABLE
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { onSpeaking(true) } }
            override fun onDone(id: String?) { main.post { onSpeaking(false) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { onSpeaking(false) } }
        })
        applyVoice()
    }

    private fun isFemale(v: Voice): Boolean {
        val n = (v.name + " " + v.features.joinToString()).lowercase()
        return n.contains("female") || n.contains("#female") || n.contains("woman") ||
            Regex("ar-xa-x-(arc|arz)").containsMatchIn(n) // Google TTS female Arabic voice ids
    }

    fun options(): List<Opt> {
        val l = ArrayList<Opt>()
        arVoices.forEach { v ->
            val g = if (isFemale(v)) "👩 أنثى" else "🗣"
            val net = if (v.isNetworkConnectionRequired) " (إنترنت)" else ""
            l.add(Opt("tts:${v.name}", "$g ${v.locale.displayName} – ${v.name}$net"))
        }
        if (arVoices.isEmpty() && ttsReady) l.add(Opt("tts:", "🗣 الصوت العربي الافتراضي للجهاز"))
        l.add(Opt("online:google", "🌐👩 صوت جوجل العربي الأنثوي (أونلاين، مجاني)"))
        return l
    }

    private fun useOnline(): Boolean = store.voice.startsWith("online:") || !ttsReady

    private fun applyVoice() {
        val t = tts ?: return
        val k = store.voice
        val v = if (k.startsWith("tts:")) arVoices.firstOrNull { it.name == k.removePrefix("tts:") } else null
        try {
            if (v != null) t.voice = v else if (arVoices.isNotEmpty()) t.voice = arVoices.first() else t.language = Locale("ar")
        } catch (_: Exception) {}
        t.setSpeechRate(store.rate); t.setPitch(store.pitch)
    }

    fun refresh() { applyVoice() }

    /** Speaks Arabic text. [flush]=true interrupts (used for live captions so speech never lags behind video). */
    fun speak(text: String, flush: Boolean) {
        val chunks = TextUtil.chunk(text, if (useOnline()) 180 else 3000)
        if (chunks.isEmpty()) return
        if (useOnline()) {
            if (flush) stopOnline()
            queue.addAll(chunks)
            if (player == null) playNext()
            return
        }
        applyVoice()
        val t = tts ?: return
        chunks.forEachIndexed { i, c ->
            val mode = if (flush && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            t.speak(c, mode, Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }, "u${++seq}")
        }
    }

    private fun playNext() {
        val text = queue.removeFirstOrNull() ?: run { player = null; onSpeaking(false); return }
        val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=ar&q=" + URLEncoder.encode(text, "UTF-8")
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(app, Uri.parse(url), mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://translate.google.com/"))
            mp.setOnPreparedListener {
                try { it.playbackParams = PlaybackParams().setSpeed(store.rate.coerceIn(0.5f, 2f)).setPitch(store.pitch.coerceIn(0.5f, 2f)) } catch (_: Exception) {}
                it.start(); onSpeaking(true)
            }
            mp.setOnCompletionListener { it.release(); if (player === it) { player = null; playNext() } }
            mp.setOnErrorListener { p, _, _ -> p.release(); if (player === p) { player = null; playNext() }; true }
            mp.prepareAsync()
        } catch (_: Exception) { mp.release(); player = null; playNext() }
    }

    private fun stopOnline() {
        queue.clear()
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }

    fun stop() { stopOnline(); try { tts?.stop() } catch (_: Exception) {}; onSpeaking(false) }

    fun shutdown() { stop(); try { tts?.shutdown() } catch (_: Exception) {} }

    fun status(): String = when {
        !ttsReady && engines.isEmpty() -> "لا يوجد محرك تحويل نص إلى كلام مثبت على الجهاز ← سيُستخدم صوت جوجل الأونلاين."
        !ttsReady -> "المحركات المثبتة: ${engines.joinToString()} — لا يوجد صوت عربي ← سيُستخدم الصوت الأونلاين."
        else -> "أصوات عربية مثبتة: ${arVoices.size} (المحركات: ${engines.joinToString()})"
    }
}
