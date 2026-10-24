package com.waseef.questaibrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import java.util.concurrent.Executors

class MainActivity : Activity() {

    class Tab(val id: Int, var url: String, var title: String = "") {
        var web: WebView? = null
        var state: Bundle? = null
        var lastUsed = System.currentTimeMillis()
        var chip: View? = null
        var progress = 100
    }

    private lateinit var store: Store
    private lateinit var speaker: Speaker
    private val audioFx = AudioFx()
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var fsContainer: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabStrip: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var starBtn: Button
    private lateinit var dubBtn: Button
    private lateinit var deskBtn: Button
    private lateinit var subtitle: TextView
    private lateinit var speakPill: Button
    private lateinit var showBars: Button

    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private var nextId = 1

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pendingPerm: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    private var dubOn = false
    private var speaking = false
    private var lastAudio = Triple(-1, 0, 0) // hooked, skipped, unused

    private val defaultUa by lazy { WebSettings.getDefaultUserAgent(this) }
    private fun asset(name: String) = assets.open(name).bufferedReader().use { it.readText() }
    private val jsTranslate by lazy { asset("translate.js") }
    private val jsDub by lazy { asset("dub.js") }
    private val jsAudio by lazy { asset("audio.js") }
    private val jsVr by lazy { asset("vr360.js") }

    // ───────────────────────────── lifecycle ─────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        speaker = Speaker(this, store) { s -> onSpeaking(s) }
        WebView.setWebContentsDebuggingEnabled(false)
        buildUi()
        val restore = store.p.getString("openTabs", null)
        val incoming = urlFromIntent(intent)
        if (restore != null) {
            try { val a = JSONArray(restore); for (i in 0 until a.length()) addTab(a.getString(i), select = false) } catch (_: Exception) {}
        }
        if (incoming != null) addTab(incoming, true)
        else if (tabs.isEmpty()) addTab(store.home, true)
        else selectTab(tabs[store.p.getInt("curTab", 0).coerceIn(0, tabs.size - 1)])
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFromIntent(intent)?.let { addTab(it, true) }
    }

    private fun urlFromIntent(i: Intent?): String? = when (i?.action) {
        Intent.ACTION_VIEW -> i.dataString
        Intent.ACTION_WEB_SEARCH -> i.getStringExtra("query")?.let { Engines.toUrl(it) }
        else -> null
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
        val a = JSONArray(); tabs.filter { !it.url.startsWith("about:") }.takeLast(20).forEach { a.put(it.url) }
        store.p.edit().putString("openTabs", a.toString()).putInt("curTab", tabs.indexOf(cur).coerceAtLeast(0)).apply()
    }

    override fun onDestroy() {
        speaker.shutdown(); audioFx.release(); io.shutdownNow()
        tabs.forEach { it.web?.destroy() }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) discardBackground(keep = 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> customCb?.onCustomViewHidden()
            chrome.visibility != View.VISIBLE -> setBarsVisible(true)
            cur?.web?.canGoBack() == true -> cur?.web?.goBack()
            tabs.size > 1 -> cur?.let { closeTab(it) }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ───────────────────────────── UI ─────────────────────────────

    private fun dp(v: Int) = Ui.dp(this, v)

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        chrome = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BAR) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1) search-engine row
        val engScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = true; isFillViewport = false }
        val engRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(4)) }
        engRow.addView(engineTile(null))
        Engines.ALL.forEach { engRow.addView(engineTile(it)) }
        engScroll.addView(engRow)
        chrome.addView(engScroll)

        // 2) navigation row
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(4)) }
        fun add(v: View, w: Int = -2) = nav.addView(v, LinearLayout.LayoutParams(w, dp(52)).apply { marginEnd = dp(4) })
        add(Ui.btn(this, "→", "رجوع") { cur?.web?.let { if (it.canGoBack()) it.goBack() } })
        add(Ui.btn(this, "←", "تقدم") { cur?.web?.let { if (it.canGoForward()) it.goForward() } })
        add(Ui.btn(this, "⟳", "تحديث") { cur?.web?.reload() })
        add(Ui.btn(this, "🏠", "الصفحة الرئيسية") { load(store.home) })
        urlBar = EditText(this).apply {
            setSingleLine(); textSize = 17f; setTextColor(Color.WHITE); setHintTextColor(0xFF8A93A3.toInt())
            hint = "اكتب عنوانًا أو كلمة بحث…"; inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            background = Ui.round(0xFF0E1116.toInt(), dp(12).toFloat(), 0xFF3A4352.toInt(), dp(1)); setPadding(dp(14), 0, dp(14), 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { load(Engines.toUrl(text.toString())); hideKb(this); clearFocus(); true } else false }
        }
        nav.addView(urlBar, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(4) })
        starBtn = Ui.btn(this, "☆", "إضافة للمفضلة") { cur?.let { t -> val on = store.toggleBookmark(t.title, t.url); toast(if (on) "أضيفت للمفضلة" else "حُذفت من المفضلة"); updateStar() } }
        add(starBtn)
        add(Ui.btn(this, "🌐 ترجمة", "ترجمة إلى العربية") { translateMenu() })
        dubBtn = Ui.btn(this, "🎙 دبلجة", "دبلجة وقراءة بصوت عربي") { dubMenu() }; add(dubBtn)
        add(Ui.btn(this, "🎚 صوت", "معزز الصوت والمعادل") { audioMenu() })
        add(Ui.btn(this, "🥽 360°", "عارض فيديو 360/180") { vrMenu() })
        deskBtn = Ui.btn(this, "", "وضع سطح المكتب") { toggleDesktop() }; add(deskBtn); updateDeskBtn()
        add(Ui.btn(this, "⋮", "المزيد") { moreMenu() })
        chrome.addView(nav)

        // 3) tab strip
        tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), dp(4)) }
        tabScroll.addView(tabStrip)
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tabRow.addView(Ui.btn(this, "＋", "لسان جديد", Ui.ACC) { addTab(store.home, true) }, LinearLayout.LayoutParams(dp(56), dp(48)).apply { marginStart = dp(6) })
        tabRow.addView(tabScroll, LinearLayout.LayoutParams(0, dp(52), 1f))
        chrome.addView(tabRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACC) }
        chrome.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))

        column.addView(chrome, LinearLayout.LayoutParams(-1, -2))
        content = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        fsContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(fsContainer, FrameLayout.LayoutParams(-1, -1))

        subtitle = TextView(this).apply {
            textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 0f, Color.BLACK); background = Ui.round(0xAA000000.toInt(), dp(12).toFloat())
            setPadding(dp(18), dp(8), dp(18), dp(8)); visibility = View.GONE; maxLines = 3
        }
        root.addView(subtitle, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(70); leftMargin = dp(40); rightMargin = dp(40) })

        speakPill = Ui.btn(this, "⏹ إيقاف الصوت", "إيقاف القراءة", 0xFFC62828.toInt()) { speaker.stop() }.apply { visibility = View.GONE }
        root.addView(speakPill, FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })

        showBars = Ui.btn(this, "☰", "إظهار الأشرطة", 0xCC1A1F27.toInt()) { setBarsVisible(true) }.apply { visibility = View.GONE }
        root.addView(showBars, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.TOP or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })

        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); ins
        }
    }

    /** One engine tile: label on top, its own search field below. [e]=null → "search all engines". */
    @SuppressLint("ClickableViewAccessibility")
    private fun engineTile(e: Engine?): View {
        val color = e?.color ?: Ui.ACC
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Ui.TILE, dp(12).toFloat(), color, dp(2))
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        val name = TextView(this).apply {
            text = e?.let { (if (it.ai) "✨ " else "") + it.label } ?: "🔎 كل المحركات"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color); gravity = Gravity.CENTER
            setSingleLine(); tooltipText = e?.why ?: "ابحث في جميع المحركات دفعة واحدة"
        }
        val field = EditText(this).apply {
            setSingleLine(); textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(0xFF7C8594.toInt())
            hint = if (e == null) "ابحث في الكل…" else "ابحث…"
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT
            background = Ui.round(0xFF0E1116.toInt(), dp(10).toFloat()); setPadding(dp(10), 0, dp(10), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        field.setOnTouchListener { _, ev ->
            // Tapping an EMPTY field (not already being edited) opens the same content in a new tab.
            if (ev.action == MotionEvent.ACTION_UP && e != null && store.dupOnTap && field.text.isBlank() && !field.hasFocus()) {
                main.post { openSameContent(e) }
            }
            false
        }
        field.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val q = field.text.toString().trim()
                if (e == null) {
                    if (q.isNotEmpty()) { Engines.ALL.forEach { addTab(Engines.searchUrl(it, q), it == Engines.ALL.first()) }; toast("فُتح البحث في ${Engines.ALL.size} محركات") }
                } else if (q.isEmpty()) openSameContent(e) else addTab(Engines.searchUrl(e, q), true)
                field.setText(""); hideKb(field); field.clearFocus(); true
            } else false
        }
        tile.addView(name, LinearLayout.LayoutParams(-1, dp(24)))
        tile.addView(field, LinearLayout.LayoutParams(-1, dp(48)))
        tile.layoutParams = LinearLayout.LayoutParams(dp(if (e == null) 170 else 150), -2).apply { marginEnd = dp(6) }
        return tile
    }

    /** "Same content" in a new tab: if the current page is a results page, re-run that query on [e]; otherwise duplicate the page. */
    private fun openSameContent(e: Engine) {
        val url = cur?.url ?: store.home
        val q = Engines.extractQuery(url)
        if (q != null) { addTab(Engines.searchUrl(e, q), true); toast("نفس البحث «$q» في ${e.label}") }
        else { addTab(url, true); toast("نسخة من الصفحة الحالية في لسان جديد") }
    }

    private fun setBarsVisible(v: Boolean) {
        chrome.visibility = if (v) View.VISIBLE else View.GONE
        showBars.visibility = if (v) View.GONE else View.VISIBLE
    }

    private fun updateDeskBtn() { deskBtn.text = if (store.desktop) "🖥 مكتبي" else "📱 جوال" }

    private fun updateStar() { cur?.let { starBtn.text = if (store.isBookmarked(it.url)) "★" else "☆" } }

    private fun hideKb(v: View) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ───────────────────────────── tabs ─────────────────────────────

    private fun addTab(url: String, select: Boolean, emptyWeb: Boolean = false): Tab {
        val t = Tab(nextId++, url)
        if (emptyWeb) t.web = makeWeb(t) // popup target: must not have navigated yet
        val idx = cur?.let { tabs.indexOf(it) + 1 } ?: tabs.size
        tabs.add(idx.coerceIn(0, tabs.size), t)
        t.chip = makeChip(t)
        tabStrip.addView(t.chip, tabs.indexOf(t))
        if (select) selectTab(t)
        return t
    }

    private fun makeChip(t: Tab): View {
        val chip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(2), 0) }
        val title = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(190); tag = "title" }
        val x = Ui.btn(this, "✕", "إغلاق اللسان", Color.TRANSPARENT) { closeTab(t) }.apply { textSize = 15f; minWidth = dp(44); minimumWidth = dp(44) }
        chip.addView(title); chip.addView(x, LinearLayout.LayoutParams(dp(44), dp(44)))
        chip.setOnClickListener { selectTab(t) }
        Ui.hover(chip)
        chip.layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(6) }
        refreshChip(t, chip)
        return chip
    }

    private fun refreshChip(t: Tab, chip: View? = t.chip) {
        val c = chip as? LinearLayout ?: return
        (c.findViewWithTag<TextView>("title"))?.text = t.title.ifBlank { t.url.removePrefix("https://").take(30) }
        c.background = Ui.round(if (t === cur) 0xFF2F4C86.toInt() else Ui.TILE, dp(10).toFloat())
        c.alpha = if (t.web == null && t !== cur) 0.6f else 1f
    }

    private fun selectTab(t: Tab) {
        val prev = cur
        cur = t; t.lastUsed = System.currentTimeMillis()
        val w = ensureWeb(t)
        content.removeAllViews()
        (w.parent as? ViewGroup)?.removeView(w)
        content.addView(w, FrameLayout.LayoutParams(-1, -1))
        w.onResume()
        if (prev != null && prev !== t) prev.web?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        urlBar.setText(displayUrl(t.url)); progress.progress = t.progress
        progress.visibility = if (t.progress < 100) View.VISIBLE else View.INVISIBLE
        tabs.forEach { refreshChip(it) }
        updateStar()
        t.chip?.let { c -> tabScroll.post { tabScroll.smoothScrollTo(c.left - dp(40), 0) } }
        discardBackground(store.maxLive)
    }

    private fun displayUrl(u: String) = if (u == Store.HOME) "" else u

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        tabs.removeAt(i); tabStrip.removeView(t.chip)
        t.web?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        t.web = null
        if (tabs.isEmpty()) { cur = null; addTab(store.home, true) }
        else if (t === cur) { cur = null; selectTab(tabs[(i - 1).coerceAtLeast(0).coerceAtMost(tabs.size - 1)]) }
    }

    /** Keeps at most [keep] background tabs alive; others are discarded (history kept, renderer memory freed). */
    private fun discardBackground(keep: Int) {
        val bg = tabs.filter { it !== cur && it.web != null }.sortedByDescending { it.lastUsed }
        bg.drop(keep).forEach { t ->
            val w = t.web ?: return@forEach
            t.state = Bundle().also { w.saveState(it) }
            (w.parent as? ViewGroup)?.removeView(w); w.stopLoading(); w.destroy(); t.web = null
            refreshChip(t)
        }
    }

    private fun ensureWeb(t: Tab): WebView {
        t.web?.let { return it }
        val w = makeWeb(t); t.web = w
        val st = t.state
        if (st == null || w.restoreState(st) == null) w.loadUrl(t.url)
        t.state = null
        return w
    }

    private fun load(url: String) { cur?.let { ensureWeb(it).loadUrl(url) } ?: addTab(url, true) }

    private fun applyUa(w: WebView) {
        val s = w.settings
        s.userAgentString = if (store.desktop) TextUtil.desktopUa(defaultUa) else TextUtil.mobileUa(defaultUa)
        s.useWideViewPort = true; s.loadWithOverviewMode = true
    }

    private fun toggleDesktop() {
        store.desktop = !store.desktop; updateDeskBtn()
        tabs.forEach { t -> t.web?.let { applyUa(it) } }
        cur?.web?.reload()
        toast(if (store.desktop) "وضع سطح المكتب (Chrome/Edge)" else "وضع الجوال")
    }

    // ───────────────────────────── WebView ─────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(t: Tab): WebView {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true; displayZoomControls = false; setSupportZoom(true)
            allowFileAccess = false; allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            safeBrowsingEnabled = false // needs Google Play Services, which Horizon OS does not have
            offscreenPreRaster = false
            textZoom = 100
        }
        applyUa(w)
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(w, true) }
        setSearchCookies()
        w.isFocusable = true; w.isFocusableInTouchMode = true
        w.addJavascriptInterface(Bridge(t), "QAB")
        w.webViewClient = Client(t)
        w.webChromeClient = Chrome(t)
        w.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
        w.setOnLongClickListener { linkMenu(w) }
        return w
    }

    private var cookiesSet = false
    /** SafeSearch-off cookies for engines that also read the preference from cookies. */
    private fun setSearchCookies() {
        if (cookiesSet) return; cookiesSet = true
        val cm = CookieManager.getInstance()
        cm.setCookie("https://www.bing.com", "SRCHHPGUSR=ADLT=OFF; Domain=.bing.com; Path=/; Max-Age=31536000")
        cm.setCookie("https://search.brave.com", "safesearch=off; Path=/; Max-Age=31536000")
        cm.setCookie("https://duckduckgo.com", "p=-2; Path=/; Max-Age=31536000")
        cm.setCookie("https://www.mojeek.com", "safe=0; Path=/; Max-Age=31536000")
    }

    private inner class Client(val t: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url; val s = u.scheme?.lowercase() ?: return false
            if (s in setOf("http", "https", "about", "data", "blob", "javascript", "file")) return false
            if (s == "intent") {
                try {
                    val i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    try { i.addCategory(Intent.CATEGORY_BROWSABLE); i.component = null; i.selector = null; startActivity(i) }
                    catch (_: Exception) { i.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) } }
                } catch (_: Exception) {}
                return true
            }
            try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { toast("لا يوجد تطبيق لفتح: $s") }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            t.url = url; t.progress = 5
            if (t === cur) { urlBar.setText(displayUrl(url)); updateStar() }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            t.url = url
            if (t === cur && !urlBar.hasFocus()) { urlBar.setText(displayUrl(url)); updateStar() }
        }

        override fun onPageFinished(view: WebView, url: String) {
            t.url = url; t.title = view.title ?: t.title
            refreshChip(t)
            store.addHistory(t.title, url)
            if (dubOn) view.evaluateJavascript(jsDub, null)
            if (store.gain != 100 || store.preset != "flat" || store.normalize) injectAudio(view)
            if (store.autoTranslate && !url.startsWith("file:")) view.evaluateJavascript(jsTranslate, null)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            AlertDialog.Builder(this@MainActivity).setTitle("⚠️ شهادة أمان غير صالحة")
                .setMessage("${error.url}\nالاتصال بهذا الموقع غير آمن. هل تريد المتابعة؟")
                .setPositiveButton("متابعة") { _, _ -> handler.proceed() }
                .setNegativeButton("رجوع") { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }.show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Renderer crashed or was killed for memory: drop this WebView and rebuild the tab instead of crashing the app.
            if (t.web === view) {
                (view.parent as? ViewGroup)?.removeView(view); view.destroy(); t.web = null; t.state = null
                if (t === cur) { main.post { selectTab(t) } }
                refreshChip(t)
            } else { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
            return true
        }
    }

    private inner class Chrome(val t: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            t.progress = p
            if (t === cur) { progress.progress = p; progress.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE }
        }

        override fun onReceivedTitle(view: WebView, title: String?) { t.title = title ?: ""; refreshChip(t) }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val nt = addTab("about:blank", true, emptyWeb = true)
            (resultMsg.obj as WebView.WebViewTransport).webView = nt.web
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) { tabs.firstOrNull { it.web === window }?.let { closeTab(it) } }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view; customCb = callback
            fsContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
            fsContainer.visibility = View.VISIBLE
            if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        }

        override fun onHideCustomView() {
            fsContainer.removeAllViews(); fsContainer.visibility = View.GONE
            customView = null; customCb = null
            if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars())
        }

        override fun onPermissionRequest(request: PermissionRequest) { main.post { handlePermission(request) } }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) callback.invoke(origin, true, false)
            else { pendingGeo = origin to callback; requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_GEO) }
        }

        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCb?.onReceiveValue(null); fileCb = cb
            val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(params.acceptTypes.firstOrNull { it.isNotBlank() }?.let { if (it.startsWith(".")) "*/*" else it } ?: "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            return try { startActivityForResult(Intent.createChooser(i, "اختر ملفًا"), REQ_FILE); true }
            catch (_: Exception) { fileCb = null; false }
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    // ───────────────────────────── permissions / files ─────────────────────────────

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun handlePermission(r: PermissionRequest) {
        val need = ArrayList<String>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in r.resources && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in r.resources && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isEmpty()) { r.grant(r.resources); return }
        pendingPerm?.deny(); pendingPerm = r
        requestPermissions(need.toTypedArray(), REQ_MEDIA)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        when (code) {
            REQ_MEDIA -> pendingPerm?.let { r ->
                val ok = r.resources.filter { when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> has(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> has(Manifest.permission.RECORD_AUDIO)
                    else -> true } }
                if (ok.isEmpty()) r.deny() else r.grant(ok.toTypedArray())
                pendingPerm = null
            }
            REQ_GEO -> pendingGeo?.let { (o, cb) -> cb.invoke(o, has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION), false); pendingGeo = null }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val cb = fileCb ?: return; fileCb = null
        if (resultCode != RESULT_OK || data == null) { cb.onReceiveValue(null); return }
        val clip = data.clipData
        val uris = if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        cb.onReceiveValue(uris)
    }

    // ───────────────────────────── downloads ─────────────────────────────

    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, cd, mime)
        when {
            url.startsWith("blob:") -> cur?.web?.evaluateJavascript(
                "(function(){fetch(${JSONArray().put(url).toString().trim('[', ']')}).then(r=>r.blob()).then(b=>{var f=new FileReader();f.onload=()=>QAB.saveData(f.result,${JSONArray().put(name).toString().trim('[', ']')});f.readAsDataURL(b);}).catch(e=>QAB.toast('تعذر تنزيل الملف: '+e));})()", null)
            url.startsWith("data:") -> saveData(url, name)
            else -> try {
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    addRequestHeader("User-Agent", ua ?: defaultUa)
                    cur?.url?.let { addRequestHeader("Referer", it) }
                    if (!mime.isNullOrBlank()) setMimeType(mime)
                    setTitle(name); setDescription(url)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                val id = dm.enqueue(req)
                val ids = store.p.getString("dl", "") ?: ""
                store.p.edit().putString("dl", (listOf(id.toString()) + ids.split(',').filter { it.isNotBlank() }).take(100).joinToString(",")).apply()
                toast("بدأ التنزيل: $name")
            } catch (e: Exception) { toast("تعذر التنزيل: ${e.message}") }
        }
    }

    private fun saveData(dataUrl: String, name: String) {
        try {
            val comma = dataUrl.indexOf(','); val meta = dataUrl.substring(5, comma)
            val bytes = if (meta.endsWith(";base64")) Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT) else Uri.decode(dataUrl.substring(comma + 1)).toByteArray()
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, meta.removeSuffix(";base64").ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("MediaStore")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            toast("حُفظ في التنزيلات: $name")
        } catch (e: Exception) { toast("تعذر الحفظ: ${e.message}") }
    }

    private fun downloadsDialog() {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val ids = (store.p.getString("dl", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) { toast("لا توجد تنزيلات بعد"); return }
        val rows = ArrayList<Pair<Long, String>>()
        dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val s = when (st) { DownloadManager.STATUS_SUCCESSFUL -> "✅"; DownloadManager.STATUS_FAILED -> "❌"; DownloadManager.STATUS_PAUSED -> "⏸"
                    else -> if (tot > 0) "⏬ ${done * 100 / tot}%" else "⏬" }
                rows.add(id to "$s  $title")
            }
        }
        AlertDialog.Builder(this).setTitle("التنزيلات (مجلد Download)").setItems(rows.map { it.second }.toTypedArray()) { _, i ->
            val id = rows[i].first
            val uri = dm.getUriForDownloadedFile(id)
            if (uri == null) toast("الملف غير جاهز") else try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { toast("لا يوجد تطبيق لفتح هذا الملف") }
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun linkMenu(w: WebView): Boolean {
        val h = w.hitTestResult
        val url = h.extra ?: return false
        val isImg = h.type == WebView.HitTestResult.IMAGE_TYPE || h.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val isLink = h.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || h.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        if (!isImg && !isLink) return false
        val items = arrayListOf("فتح في لسان جديد", "فتح في الخلفية", "نسخ الرابط")
        if (isImg) items.add("تنزيل الصورة")
        AlertDialog.Builder(this).setTitle(url.take(80)).setItems(items.toTypedArray()) { _, i ->
            when (i) { 0 -> addTab(url, true); 1 -> { addTab(url, false); toast("فُتح في الخلفية") }
                2 -> copy(url); 3 -> download(url, null, null, null) }
        }.show()
        return true
    }

    private fun copy(s: String) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", s)); toast("نُسخ") }

    // ───────────────────────────── JS bridge ─────────────────────────────

    inner class Bridge(private val t: Tab) {
        @JavascriptInterface fun translate(id: Int, json: String) {
            io.execute {
                val arr = try { JSONArray(json) } catch (_: Exception) { return@execute }
                val items = (0 until arr.length()).map { arr.optString(it) }
                val res = try { Translator.translateBatch(items) } catch (_: Exception) { items }
                val out = JSONArray(); res.forEach { out.put(it) }
                main.post { t.web?.evaluateJavascript("window.__qabTr&&window.__qabTr($id,$out)", null) }
            }
        }
        @JavascriptInterface fun caption(text: String) {
            if (!dubOn) return
            io.execute {
                val ar = try { Translator.translate(text) } catch (_: Exception) { text }
                main.post { if (dubOn && t === cur) { showSubtitle(ar); speaker.speak(ar, flush = true) } }
            }
        }
        @JavascriptInterface fun vrFail(src: String, reason: String) { main.post { vrFallback(src, reason) } }
        @JavascriptInterface fun audioStatus(hooked: Int, skipped: Int) { main.post { lastAudio = Triple(hooked, skipped, 0) } }
        @JavascriptInterface fun saveData(dataUrl: String, name: String) { main.post { this@MainActivity.saveData(dataUrl, name) } }
        @JavascriptInterface fun toast(s: String) { main.post { this@MainActivity.toast(s) } }
    }

    private val hideSub = Runnable { subtitle.visibility = View.GONE }
    private fun showSubtitle(s: String) {
        subtitle.text = s; subtitle.visibility = View.VISIBLE
        main.removeCallbacks(hideSub); main.postDelayed(hideSub, 6000)
    }

    private fun onSpeaking(s: Boolean) {
        speaking = s
        speakPill.visibility = if (s) View.VISIBLE else View.GONE
        if (dubOn) cur?.web?.evaluateJavascript("window.__qabDuck&&window.__qabDuck($s,${store.duck})", null)
    }

    // ───────────────────────────── translation ─────────────────────────────

    private fun translateMenu() {
        val items = arrayOf(
            "🌐 ترجمة هذه الصفحة إلى العربية (داخل الصفحة، تحافظ على تسجيل الدخول)",
            "🔁 ترجمة كاملة عبر وكيل Google Translate",
            (if (store.autoTranslate) "✅" else "⬜") + " ترجمة تلقائية لكل صفحة",
            "🗣 ترجمة النص المحدد ونطقه",
            "↩️ عرض الصفحة الأصلية")
        AlertDialog.Builder(this).setTitle("الترجمة الذكية إلى العربية").setItems(items) { _, i ->
            val w = cur?.web ?: return@setItems
            when (i) {
                0 -> { w.evaluateJavascript(jsTranslate, null); toast("جاري الترجمة…") }
                1 -> cur?.url?.let { if (it.startsWith("http")) w.loadUrl("https://translate.google.com/translate?sl=auto&tl=ar&hl=ar&u=" + Uri.encode(it)) }
                2 -> { store.autoTranslate = !store.autoTranslate; toast(if (store.autoTranslate) "الترجمة التلقائية مفعلة" else "الترجمة التلقائية متوقفة"); if (store.autoTranslate) w.evaluateJavascript(jsTranslate, null) }
                3 -> w.evaluateJavascript("(window.getSelection()||'').toString()") { r -> val s = jsString(r); if (s.isBlank()) toast("حدد نصًا أولاً") else translateAndSpeak(s, true) }
                4 -> w.reload()
            }
        }.show()
    }

    private fun jsString(r: String?): String = try { JSONArray("[$r]").optString(0, "") } catch (_: Exception) { "" }

    private fun translateAndSpeak(text: String, showDialog: Boolean) {
        toast("جاري الترجمة والقراءة…")
        io.execute {
            val chunks = TextUtil.chunk(text, 450)
            var first = true
            for (group in chunks.chunked(8)) {
                val ar = try { Translator.translateBatch(group) } catch (_: Exception) { group }
                val joined = ar.joinToString(" ")
                val isFirst = first; first = false
                main.post {
                    if (showDialog && isFirst) AlertDialog.Builder(this).setMessage(joined).setPositiveButton("إغلاق", null).show()
                    speaker.speak(joined, flush = isFirst)
                }
            }
        }
    }

    // ───────────────────────────── dubbing / voices ─────────────────────────────

    private fun dubMenu() {
        val box = Ui.vbox(this)
        val status = Ui.label(this, speaker.status(), 15f, 0xFFB0BEC5.toInt())
        box.addView(status)
        val toggle = Ui.btn(this, if (dubOn) "⏹ إيقاف الدبلجة الحية" else "🎬 تشغيل الدبلجة الحية للفيديو (ترجمة الترجمات النصية وقراءتها)", null, if (dubOn) 0xFFC62828.toInt() else Ui.ON) {}
        box.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val readBtn = Ui.btn(this, "📖 قراءة الصفحة بصوت عربي (مع الترجمة)", null, Ui.ACC) {}
        box.addView(readBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        box.addView(Ui.label(this, "اختر الصوت (الأصوات النسائية أولاً):", 17f))
        val opts = speaker.options()
        val group = RadioGroup(this)
        opts.forEachIndexed { i, o ->
            group.addView(RadioButton(this).apply { id = 1000 + i; text = o.label; textSize = 16f; setTextColor(Color.WHITE); minHeight = dp(48)
                isChecked = store.voice == o.key || (store.voice.isEmpty() && i == 0) })
        }
        group.setOnCheckedChangeListener { _, id -> store.voice = opts[id - 1000].key; speaker.refresh() }
        box.addView(group)
        box.addView(Ui.seek(this, "السرعة", 50, 200, (store.rate * 100).toInt(), { "${it}%" }) { store.rate = it / 100f; speaker.refresh() })
        box.addView(Ui.seek(this, "طبقة الصوت (أعلى = أنعم)", 50, 200, (store.pitch * 100).toInt(), { "${it}%" }) { store.pitch = it / 100f; speaker.refresh() })
        box.addView(Ui.seek(this, "صوت الفيديو الأصلي أثناء الدبلجة", 0, 100, (store.duck * 100).toInt(), { "${it}%" }) { store.duck = it / 100f })
        box.addView(Ui.btn(this, "▶️ تجربة الصوت", null) { speaker.speak("مرحباً بك في متصفح كويست الذكي. هذا هو الصوت العربي المختار.", true) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(Ui.label(this, "ملاحظة: نظارة كويست قد لا تحتوي على محرك أصوات عربي. يمكنك تثبيت محرك TTS (مثل eSpeak NG أو RHVoice أو Google TTS) كملف APK، أو استخدام صوت جوجل الأونلاين المجاني. الدبلجة الحية تعمل عندما يحتوي الفيديو على ترجمة نصية (CC) — في يوتيوب فعّل زر CC.", 14f, 0xFF9AA5B1.toInt()))
        Ui.hoverTree(box)
        val dlg = AlertDialog.Builder(this).setTitle("🎙 الدبلجة والقراءة الصوتية العربية").setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("إغلاق", null).show()
        toggle.setOnClickListener { setDub(!dubOn); dlg.dismiss() }
        readBtn.setOnClickListener { readPage(); dlg.dismiss() }
    }

    private fun setDub(on: Boolean) {
        dubOn = on
        dubBtn.background = Ui.round(if (on) Ui.ON else Ui.TILE, dp(12).toFloat())
        val w = cur?.web
        if (on) {
            w?.evaluateJavascript(jsDub, null)
            toast("الدبلجة الحية مفعلة — شغّل فيديو بترجمة نصية (CC)")
        } else {
            w?.evaluateJavascript("window.__qabDub&&window.__qabDub.off()", null); speaker.stop(); subtitle.visibility = View.GONE
        }
    }

    private fun readPage() {
        val js = "(function(){var s=(window.getSelection()||'').toString();if(s.trim().length>40)return s;" +
            "var r=document.querySelector('article')||document.querySelector('main')||document.body;var t=[];" +
            "r.querySelectorAll('h1,h2,h3,p,li,blockquote').forEach(function(p){var x=(p.innerText||'').trim();if(x.length>30&&p.offsetParent!==null)t.push(x);});" +
            "var o=t.join('\\n');if(o.length<200)o=r.innerText||'';return o.slice(0,20000);})()"
        cur?.web?.evaluateJavascript(js) { r -> val s = jsString(r); if (s.isBlank()) toast("لا يوجد نص للقراءة") else translateAndSpeak(s, false) }
    }

    // ───────────────────────────── audio tools ─────────────────────────────

    private fun injectAudio(w: WebView) {
        val cfg = "{gain:${store.gain},preset:'${store.preset}',norm:${store.normalize}}"
        w.evaluateJavascript("($jsAudio)($cfg)", null)
        audioFx.apply(store.gain)
    }

    private fun audioMenu() {
        val box = Ui.vbox(this)
        val status = Ui.label(this, "", 14f, 0xFFB0BEC5.toInt())
        fun upd() {
            cur?.web?.let { injectAudio(it) }
            main.postDelayed({
                val (h, s, _) = lastAudio
                status.text = "مقاطع معززة داخل الصفحة: ${h.coerceAtLeast(0)} • غير قابلة للمعالجة (محمية/خارجية): $s\nمعزز النظام (LoudnessEnhancer): " +
                    (if (store.gain <= 100) "غير مستخدم" else if (audioFx.nativeOk) "يعمل ✅" else "غير مدعوم على هذا الجهاز")
            }, 400)
        }
        box.addView(Ui.seek(this, "🔊 مستوى الصوت", 0, 300, store.gain, { "${it}%" }) { store.gain = it; upd() })
        box.addView(Ui.label(this, "المعادل (Equalizer):", 17f))
        val presets = listOf("flat" to "عادي", "voice" to "🗣 وضوح الكلام", "bass" to "🥁 تعزيز الجهير", "movie" to "🎬 أفلام", "treble" to "🎵 حدة عالية")
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        presets.forEachIndexed { i, (k, l) -> rg.addView(RadioButton(this).apply { id = 2000 + i; text = l; textSize = 17f; setTextColor(Color.WHITE); minHeight = dp(48); isChecked = store.preset == k }) }
        rg.setOnCheckedChangeListener { _, id -> store.preset = presets[id - 2000].first; upd() }
        box.addView(rg)
        box.addView(CheckBox(this).apply { text = "📏 توحيد مستوى الصوت (Normalize / ضاغط ديناميكي)"; textSize = 17f; setTextColor(Color.WHITE); minHeight = dp(48)
            isChecked = store.normalize; setOnCheckedChangeListener { _, c -> store.normalize = c; upd() } })
        box.addView(Ui.btn(this, "↺ إعادة الضبط", null) { store.gain = 100; store.preset = "flat"; store.normalize = false; upd(); toast("أُعيد الضبط") },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(status)
        box.addView(Ui.label(this, "يعمل التعزيز عبر Web Audio داخل الصفحة (يوتيوب ومعظم المشغلات). الفيديوهات القادمة من نطاق آخر بدون CORS لا يمكن معالجتها داخل الصفحة ويُستخدم لها معزز النظام إن توفر.", 14f, 0xFF9AA5B1.toInt()))
        Ui.hoverTree(box)
        AlertDialog.Builder(this).setTitle("🎚 أدوات الصوت").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("إغلاق", null).show()
        upd()
    }

    // ───────────────────────────── VR / 360 ─────────────────────────────

    private fun vrMenu() {
        val modes = listOf("360" to "🌐 فيديو الصفحة بعرض 360°", "180" to "◐ فيديو الصفحة بعرض 180°", "180sbs" to "👓 180° ثلاثي الأبعاد (SBS)",
            "360tb" to "👓 360° ثلاثي الأبعاد (أعلى/أسفل)", "360sbs" to "👓 360° ثلاثي الأبعاد (SBS)")
        val items = modes.map { it.second } + listOf("🔗 تشغيل رابط فيديو 360° في المشغل الأصلي", "🥽 فتح الصفحة في Meta Quest Browser (WebXR غامر)")
        AlertDialog.Builder(this).setTitle("عارض الواقع الافتراضي 360°/180°").setItems(items.toTypedArray()) { _, i ->
            when {
                i < modes.size -> cur?.web?.evaluateJavascript("window.__qabVrMode='${modes[i].first}';$jsVr", null)
                i == modes.size -> askUrl("رابط فيديو 360° (mp4 / m3u8)") { u -> startActivity(Intent(this, Vr360Activity::class.java).putExtra("url", u).putExtra("mode", "360")) }
                else -> openInQuestBrowser()
            }
        }.show()
    }

    private fun vrFallback(src: String, reason: String) {
        when {
            reason == "novideo" -> toast("لا يوجد فيديو في هذه الصفحة — شغّل الفيديو أولاً")
            src.startsWith("http") -> AlertDialog.Builder(this).setTitle("المشغل الأصلي 360°")
                .setMessage("هذا الفيديو محمي من المعالجة داخل الصفحة (CORS). هل تريد تشغيله في مشغل 360° الأصلي؟")
                .setPositiveButton("تشغيل") { _, _ -> startActivity(Intent(this, Vr360Activity::class.java).putExtra("url", src).putExtra("mode", "360")) }
                .setNegativeButton("إلغاء", null).show()
            else -> AlertDialog.Builder(this).setTitle("تعذر العرض 360°")
                .setMessage("هذا الفيديو محمي (DRM أو مصدر مختلف). جرّب فتح الصفحة في Meta Quest Browser الذي يدعم WebXR.")
                .setPositiveButton("فتح في Quest Browser") { _, _ -> openInQuestBrowser() }.setNegativeButton("إغلاق", null).show()
        }
    }

    private fun openInQuestBrowser() {
        val u = cur?.url ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage("com.oculus.browser")) }
        catch (_: Exception) { toast("متصفح Meta Quest غير متوفر على هذا الجهاز") }
    }

    private fun askUrl(title: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply { setSingleLine(); hint = "https://…"; textDirection = View.TEXT_DIRECTION_LTR; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        AlertDialog.Builder(this).setTitle(title).setView(et).setPositiveButton("فتح") { _, _ -> val u = et.text.toString().trim(); if (u.isNotEmpty()) onOk(u) }
            .setNegativeButton("إلغاء", null).show()
    }

    // ───────────────────────────── more menu ─────────────────────────────

    private fun moreMenu() {
        val items = arrayOf("⭐ المفضلة", "🕘 السجل", "⏬ التنزيلات", "🔍 بحث في الصفحة", "⛶ إخفاء الأشرطة (عرض غامر)",
            "📋 نسخ رابط الصفحة", "🏠 جعل هذه الصفحة الرئيسية", "⚙️ الإعدادات", "ℹ️ حول")
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> listDialog("المفضلة", store.bookmarks(), { store.removeBookmark(it.url) })
                1 -> listDialog("السجل", store.history(), null, clear = { store.clearHistory() })
                2 -> downloadsDialog()
                3 -> findInPage()
                4 -> setBarsVisible(false)
                5 -> cur?.url?.let { copy(it) }
                6 -> cur?.url?.let { store.home = it; toast("تم تعيين الصفحة الرئيسية") }
                7 -> settingsDialog()
                8 -> AlertDialog.Builder(this).setTitle(getString(R.string.app_name)).setMessage(
                    "الإصدار 1.0.0\nمحرك WebView: ${Regex("Chrome/[\\d.]+").find(defaultUa)?.value ?: "?"}\n${speaker.status()}\n\n" +
                        "ملاحظة: Android WebView لا يدعم WebXR؛ للتجارب الغامرة الحقيقية استخدم زر «فتح في Meta Quest Browser».").setPositiveButton("حسناً", null).show()
            }
        }.show()
    }

    private fun listDialog(title: String, items: List<Store.Item>, onDelete: ((Store.Item) -> Unit)?, clear: (() -> Unit)? = null) {
        if (items.isEmpty()) { toast("القائمة فارغة"); return }
        val b = AlertDialog.Builder(this).setTitle(title)
            .setItems(items.map { "${it.title.take(60)}\n${it.url.take(80)}" }.toTypedArray()) { _, i -> addTab(items[i].url, true) }
            .setNegativeButton("إغلاق", null)
        if (clear != null) b.setNeutralButton("مسح الكل") { _, _ -> clear(); toast("تم المسح") }
        val d = b.show()
        if (onDelete != null) d.listView.setOnItemLongClickListener { _, _, i, _ -> onDelete(items[i]); toast("حُذف"); d.dismiss(); true }
    }

    private fun findInPage() {
        val w = cur?.web ?: return
        val et = EditText(this).apply { setSingleLine(); hint = "كلمة البحث" }
        val d = AlertDialog.Builder(this).setTitle("بحث في الصفحة").setView(et)
            .setPositiveButton("التالي", null).setNeutralButton("السابق", null).setNegativeButton("إغلاق") { _, _ -> w.clearMatches() }.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (et.tag != et.text.toString()) { et.tag = et.text.toString(); w.findAllAsync(et.text.toString()) } else w.findNext(true) }
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { w.findNext(false) }
    }

    private fun settingsDialog() {
        val box = Ui.vbox(this)
        box.addView(CheckBox(this).apply { text = "لمس حقل محرك فارغ يفتح نفس المحتوى في لسان جديد"; textSize = 17f; setTextColor(Color.WHITE); minHeight = dp(48)
            isChecked = store.dupOnTap; setOnCheckedChangeListener { _, c -> store.dupOnTap = c } })
        box.addView(Ui.seek(this, "عدد الألسنة النشطة في الذاكرة (الباقي يُجمَّد)", 1, 8, store.maxLive, { "$it" }) { store.maxLive = it; discardBackground(it) })
        box.addView(Ui.btn(this, "🧹 مسح ذاكرة التخزين المؤقت", null) { cur?.web?.clearCache(true); toast("تم المسح") }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(Ui.btn(this, "🍪 مسح ملفات تعريف الارتباط وبيانات المواقع", null) {
            CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData(); cookiesSet = false; setSearchCookies(); toast("تم المسح") },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(Ui.btn(this, "🗣 إعدادات محرك تحويل النص إلى كلام في النظام", null) {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { toast("غير متاح على هذا الجهاز") } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        Ui.hoverTree(box)
        AlertDialog.Builder(this).setTitle("⚙️ الإعدادات").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("إغلاق", null).show()
    }

    companion object {
        private const val REQ_MEDIA = 11
        private const val REQ_GEO = 12
        private const val REQ_FILE = 13
    }
}
