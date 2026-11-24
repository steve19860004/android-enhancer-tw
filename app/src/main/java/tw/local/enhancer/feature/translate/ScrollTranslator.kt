package tw.local.enhancer.feature.translate

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.LruCache
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import tw.local.enhancer.App
import tw.local.enhancer.core.EnhancerA11yService
import tw.local.enhancer.core.Prefs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tab 5：穿透式捲動翻譯。
 * 停止捲動約 0.45 秒後讀取畫面文字（無障礙節點／OCR），用 ML Kit 離線翻譯，
 * 以不可觸控的浮層蓋在原文位置；底部膠囊條可切換語言、重翻、關閉。
 */
object ScrollTranslator {
    const val K_SRC = "t5_src"
    const val K_TGT = "t5_tgt"
    const val K_ENGINE = "t5_engine"       // hybrid | node | ocr
    const val K_FONT = "t5_font"           // 百分比，120 = 1.2x
    const val K_WIFI = "t5_wifi_only"
    private const val K_CAP_X = "t5_cap_x"
    private const val K_CAP_Y = "t5_cap_y"

    private class Item(val rect: Rect, val text: String, val lines: Int) {
        var bg = 0
        var fg = 0
        var out: String? = null
    }

    @Volatile var active = false
        private set

    private var svc: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var overlay: FrameLayout? = null
    private var capsule: View? = null
    private var capLp: WindowManager.LayoutParams? = null
    private var statusTv: TextView? = null
    private var srcTv: TextView? = null
    private var tgtTv: TextView? = null

    private var translator: Translator? = null
    private var translatorKey = ""
    private val langId by lazy { LanguageIdentification.getClient() }
    private val recognizers = HashMap<String, TextRecognizer>()
    private val cache = LruCache<String, String>(800)
    private var gen = 0
    private var lastPass = 0L
    private var shownCount = 0
    private val passRunnable = Runnable { runPass() }
    private var listeners = mutableListOf<(Boolean) -> Unit>()

    fun addStateListener(l: (Boolean) -> Unit) { listeners.add(l) }
    fun removeStateListener(l: (Boolean) -> Unit) { listeners.remove(l) }
    private fun fireState() = listeners.toList().forEach { it(active) }

    fun src() = Prefs.str(K_SRC, Langs.AUTO)
    fun tgt() = Prefs.str(K_TGT, "zh-TW")

    // ---------------- 開關 ----------------

    fun toggle(ctx: Context): Boolean = if (active) { stop(); false } else start(ctx)

    fun start(ctx: Context): Boolean {
        val s = EnhancerA11yService.instance
        if (s == null) {
            Toast.makeText(ctx, "捲動翻譯需要先開啟本 App 的「無障礙服務」", Toast.LENGTH_LONG).show()
            return false
        }
        if (active) return true
        svc = s
        wm = s.getSystemService(WindowManager::class.java)
        active = true
        App.bg { ZhConvert.s2tw("预载") }
        addOverlay(s)
        addCapsule(s)
        schedule(200)
        fireState()
        return true
    }

    fun stop() {
        if (!active) return
        active = false
        gen++
        App.main.removeCallbacks(passRunnable)
        val w = wm
        if (w != null) {
            overlay?.let { runCatching { w.removeView(it) } }
            capsule?.let { runCatching { w.removeView(it) } }
        }
        overlay = null; capsule = null; capLp = null
        statusTv = null; srcTv = null; tgtTv = null
        runCatching { translator?.close() }
        translator = null; translatorKey = ""
        svc = null
        fireState()
    }

    fun onEvent(s: AccessibilityService, e: AccessibilityEvent) {
        if (!active) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == s.packageName) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> { clearOverlay(); schedule(450) }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { clearOverlay(); schedule(650) }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 內容一直在變的畫面（影片、輪播）不要一直重翻造成閃爍
                if (shownCount == 0 && System.currentTimeMillis() - lastPass > 1500) schedule(800)
            }
        }
    }

    private fun schedule(ms: Long) {
        App.main.removeCallbacks(passRunnable)
        App.main.postDelayed(passRunnable, ms)
    }

    fun retranslateNow() {
        cache.evictAll()
        clearOverlay()
        schedule(50)
    }

    // ---------------- 浮層 ----------------

    private fun overlayParams(touchable: Boolean, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun addOverlay(s: AccessibilityService) {
        val v = FrameLayout(s)
        val lp = overlayParams(false, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        runCatching { wm?.addView(v, lp); overlay = v }
    }

    private fun clearOverlay() {
        overlay?.removeAllViews()
        shownCount = 0
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** 可拖曳的膠囊控制條：子元件可點，拖動超過門檻就整條移動 */
    @SuppressLint("ViewConstructor")
    private class DragBar(ctx: Context, val onDrag: (Int, Int) -> Unit, val onDrop: () -> Unit) : LinearLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var dx = 0f; private var dy = 0f; private var lastX = 0f; private var lastY = 0f
        private var dragging = false
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; lastX = e.rawX; lastY = e.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> if (abs(e.rawX - dx) > slop || abs(e.rawY - dy) > slop) { dragging = true; return true }
            }
            return false
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY; return true }
                MotionEvent.ACTION_MOVE -> {
                    onDrag((e.rawX - lastX).toInt(), (e.rawY - lastY).toInt())
                    lastX = e.rawX; lastY = e.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (dragging) onDrop(); dragging = false }
            }
            return true
        }
    }

    private fun addCapsule(s: AccessibilityService) {
        val dark = (s.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val fg = if (dark) Color.WHITE else Color.parseColor("#1D1D1D")
        val metrics = s.resources.displayMetrics
        lateinit var lp: WindowManager.LayoutParams
        val bar = DragBar(s, { ddx, ddy ->
            lp.x += ddx; lp.y += ddy
            capsule?.let { runCatching { wm?.updateViewLayout(it, lp) } }
        }, {
            Prefs.put(K_CAP_X, lp.x); Prefs.put(K_CAP_Y, lp.y)
        }).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(if (dark) Color.argb(235, 38, 38, 38) else Color.argb(240, 255, 255, 255))
                cornerRadius = dp(s, 22).toFloat()
                setStroke(dp(s, 1), Color.parseColor("#E8682A"))
            }
            setPadding(dp(s, 6), dp(s, 4), dp(s, 6), dp(s, 4))
            elevation = dp(s, 6).toFloat()
        }
        fun chip(text: String, color: Int = fg, onClick: () -> Unit): TextView {
            val t = TextView(s).apply {
                this.text = text
                textSize = 13.5f
                setTextColor(color)
                setPadding(dp(s, 10), dp(s, 8), dp(s, 10), dp(s, 8))
                setOnClickListener { onClick() }
            }
            bar.addView(t)
            return t
        }
        statusTv = chip("待命", Color.parseColor("#E8682A")) { retranslateNow() }
        srcTv = chip(Langs.name(src())) { pickLang(s, true) }
        chip("⇄") { swap() }
        tgtTv = chip(Langs.name(tgt())) { pickLang(s, false) }
        chip("✕", Color.parseColor("#D04545")) { stop() }

        lp = overlayParams(true, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.x = Prefs.int(K_CAP_X, metrics.widthPixels / 2 - dp(s, 150)).coerceIn(0, metrics.widthPixels - dp(s, 80))
        lp.y = Prefs.int(K_CAP_Y, metrics.heightPixels - dp(s, 170)).coerceIn(0, metrics.heightPixels - dp(s, 60))
        capLp = lp
        runCatching { wm?.addView(bar, lp); capsule = bar }
    }

    private fun setStatus(t: String) { statusTv?.text = t }

    private fun refreshLabels() {
        srcTv?.text = Langs.name(src())
        tgtTv?.text = Langs.name(tgt())
    }

    private fun swap() {
        val s = src(); val t = tgt()
        if (s == Langs.AUTO) {
            Toast.makeText(App.app, "來源為自動偵測時無法對調", Toast.LENGTH_SHORT).show(); return
        }
        Prefs.put(K_SRC, t); Prefs.put(K_TGT, s)
        refreshLabels(); retranslateNow()
    }

    private fun pickLang(s: AccessibilityService, source: Boolean) {
        val codes = Langs.NAMES.keys.filter { source || it != Langs.AUTO }
        val names = codes.map { Langs.name(it) }.toTypedArray()
        val d = AlertDialog.Builder(s, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (source) "選擇來源語系" else "選擇目標語言")
            .setItems(names) { _, which ->
                Prefs.put(if (source) K_SRC else K_TGT, codes[which])
                refreshLabels(); retranslateNow()
            }
            .create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        runCatching { d.show() }
    }

    // ---------------- 擷取文字 ----------------

    private fun runPass() {
        val s = svc ?: return
        if (!active) return
        val my = ++gen
        lastPass = System.currentTimeMillis()
        setStatus("辨識中")
        val engine = Prefs.str(K_ENGINE, "hybrid")
        val nodes = if (engine != "ocr") collectNodes(s) else mutableListOf()
        val canShot = Build.VERSION.SDK_INT >= 30
        if (engine == "node" || !canShot) {
            val d = defaultColors(s)
            nodes.forEach { it.bg = d.first; it.fg = d.second }
            translateAll(s, nodes, my)
            return
        }
        // 截圖前先藏起譯文，避免 OCR 讀到自己
        overlay?.visibility = View.INVISIBLE
        App.ui(60) {
            if (my != gen) { overlay?.visibility = View.VISIBLE; return@ui }
            screenshot(s) { bmp ->
                overlay?.visibility = View.VISIBLE
                if (my != gen) { bmp?.recycle(); return@screenshot }
                if (bmp == null) {
                    val d = defaultColors(s)
                    nodes.forEach { it.bg = d.first; it.fg = d.second }
                    translateAll(s, nodes, my)
                    return@screenshot
                }
                ocr(bmp) { blocks ->
                    if (my != gen) { bmp.recycle(); return@ocr }
                    val capRect = capsuleRect()
                    val all = ArrayList(nodes)
                    for (b in blocks) {
                        if (capRect != null && Rect.intersects(capRect, b.rect)) continue
                        // 混合模式：OCR 只補節點沒讀到的地方（多半是圖片裡的字）
                        if (all.any { overlapRatio(it.rect, b.rect) > 0.3f }) continue
                        all.add(b)
                    }
                    all.forEach { sampleColors(bmp, it) }
                    bmp.recycle()
                    translateAll(s, all, my)
                }
            }
        }
    }

    private fun capsuleRect(): Rect? {
        val c = capsule ?: return null
        val loc = IntArray(2)
        c.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + c.width, loc[1] + c.height)
    }

    private val letterRe = Regex("\\p{L}")

    private fun collectNodes(s: AccessibilityService): MutableList<Item> {
        val out = ArrayList<Item>()
        val metrics = s.resources.displayMetrics
        val screen = Rect(0, 0, metrics.widthPixels, metrics.heightPixels + dp(s, 80))
        val minH = dp(s, 8)
        val windows: List<AccessibilityWindowInfo> = runCatching { s.windows }.getOrDefault(emptyList())
        val roots = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
            .ifEmpty { listOfNotNull(s.rootInActiveWindow) }
        for (root in roots) {
            if (root.packageName?.toString() == s.packageName) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 2500 && out.size < 160) {
                val n = stack.removeLast()
                visited++
                if (!n.isVisibleToUser) continue
                for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
                if (n.isEditable) continue
                val t = n.text?.toString()?.trim() ?: continue
                if (t.length < 2 || !letterRe.containsMatchIn(t)) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.height() < minH || r.width() < minH || !Rect.intersects(r, screen)) continue
                out.add(Item(r, t, t.count { it == '\n' } + 1))
            }
        }
        // 去重：同一段文字被父子節點重複回報時保留較小的框
        val res = ArrayList<Item>()
        for (it in out.sortedBy { it.rect.width() * it.rect.height() }) {
            if (res.any { o -> o.text == it.text && Rect.intersects(o.rect, it.rect) }) continue
            if (res.any { o -> it.rect.contains(o.rect) && it.text.contains(o.text) && it.text.length - o.text.length < 3 }) continue
            res.add(it)
        }
        return res
    }

    private fun screenshot(s: AccessibilityService, cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        runCatching {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val hb = r.hardwareBuffer
                    val bmp = runCatching {
                        Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    hb.close()
                    cb(bmp)
                }
                override fun onFailure(code: Int) {
                    if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        App.ui(700) { screenshot(s, cb) }
                    } else cb(null)
                }
            })
        }.onFailure { cb(null) }
    }

    private fun recognizerFor(src: String): TextRecognizer {
        val key = when (src) {
            "ja" -> "ja"; "ko" -> "ko"
            "zh-TW", "zh-CN", Langs.AUTO -> "zh"
            else -> "latin"
        }
        return recognizers.getOrPut(key) {
            when (key) {
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    private val cjkRe = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af]")

    private fun ocr(bmp: Bitmap, cb: (List<Item>) -> Unit) {
        val minH = (8 * App.app.resources.displayMetrics.density).toInt()
        recognizerFor(src()).process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val items = text.textBlocks.mapNotNull { b ->
                    val r = b.boundingBox ?: return@mapNotNull null
                    if (r.height() < minH) return@mapNotNull null
                    val lines = b.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
                    if (lines.isEmpty()) return@mapNotNull null
                    val joined = if (lines.any { cjkRe.containsMatchIn(it) }) lines.joinToString("") else lines.joinToString(" ")
                    if (joined.length < 2 || !letterRe.containsMatchIn(joined)) return@mapNotNull null
                    Item(Rect(r), joined, lines.size)
                }
                cb(items)
            }
            .addOnFailureListener { cb(emptyList()) }
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val area = (i.width() * i.height()).toFloat()
        return area / max(1, min(a.width() * a.height(), b.width() * b.height()))
    }

    private fun defaultColors(ctx: Context): Pair<Int, Int> {
        val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (dark) Color.argb(245, 28, 28, 28) to Color.WHITE else Color.argb(245, 255, 255, 255) to Color.parseColor("#111111")
    }

    /** 取框四角外緣的顏色當背景，讓譯文和原頁面融合 */
    private fun sampleColors(bmp: Bitmap, it: Item) {
        val r = it.rect
        val pts = listOf(
            r.left + 1 to r.top + 1, r.right - 2 to r.top + 1,
            r.left + 1 to r.bottom - 2, r.right - 2 to r.bottom - 2
        )
        val colors = pts.mapNotNull { (x, y) ->
            if (x in 0 until bmp.width && y in 0 until bmp.height) bmp.getPixel(x, y) else null
        }
        val bg = colors.groupingBy { it }.eachCount().maxByOrNull { e -> e.value }?.key ?: Color.WHITE
        it.bg = Color.argb(250, Color.red(bg), Color.green(bg), Color.blue(bg))
        val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255
        it.fg = if (lum > 0.55) Color.parseColor("#111111") else Color.WHITE
    }

    // ---------------- 翻譯 ----------------

    private val conditions: DownloadConditions
        get() = if (Prefs.bool(K_WIFI, false)) DownloadConditions.Builder().requireWifi().build()
        else DownloadConditions.Builder().build()

    private fun translateAll(s: Context, items: List<Item>, my: Int) {
        if (items.isEmpty()) { setStatus("待命"); return }
        val target = tgt()
        val sample = items.joinToString("\n") { it.text }.take(800)
        if (src() == Langs.AUTO) {
            langId.identifyLanguage(sample)
                .addOnSuccessListener { tag ->
                    if (my != gen) return@addOnSuccessListener
                    val code = if (tag == "und") "en" else tag
                    translateWith(s, items, code, target, my)
                }
                .addOnFailureListener { if (my == gen) translateWith(s, items, "en", target, my) }
        } else {
            translateWith(s, items, src(), target, my)
        }
    }

    private fun hansToHant(t: String, toTrad: Boolean): String = if (toTrad) ZhConvert.s2tw(t) else ZhConvert.t2s(t)

    private fun translateWith(s: Context, items: List<Item>, srcCode: String, target: String, my: Int) {
        val srcMl = Langs.mlkit(srcCode)
        val tgtMl = Langs.mlkit(target)
        if (srcMl == null || tgtMl == null) {
            setStatus("不支援")
            return
        }
        val toTrad = Langs.needsTraditional(target)
        if (srcMl == tgtMl) {
            if (srcMl == TranslateLanguage.CHINESE) {
                // 中文頁面：只做簡繁轉換，有變化的才蓋上去
                items.forEach { val c = hansToHant(it.text, toTrad); if (c != it.text) it.out = c }
                render(items.filter { it.out != null }, my)
            } else {
                setStatus("同語言")
            }
            return
        }
        val key = "$srcMl>$tgtMl"
        if (key != translatorKey) {
            runCatching { translator?.close() }
            translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(srcMl).setTargetLanguage(tgtMl).build()
            )
            translatorKey = key
        }
        val tr = translator ?: return
        setStatus("翻譯中")
        tr.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                if (my != gen) return@addOnSuccessListener
                var left = items.size
                fun done() {
                    left--
                    if (left == 0 && my == gen) render(items.filter { it.out != null }, my)
                }
                for (it in items) {
                    val ck = key + "|" + it.text
                    val hit = cache.get(ck)
                    if (hit != null) {
                        if (hit != it.text) it.out = hit
                        done(); continue
                    }
                    tr.translate(it.text)
                        .addOnSuccessListener { res ->
                            val out = if (tgtMl == TranslateLanguage.CHINESE) hansToHant(res, toTrad) else res
                            cache.put(ck, out)
                            // 翻完和原文一樣（本來就是目標語言）就不蓋
                            if (out.trim() != it.text.trim()) it.out = out
                            done()
                        }
                        .addOnFailureListener { done() }
                }
            }
            .addOnFailureListener {
                if (my == gen) {
                    setStatus("缺語言包")
                    Toast.makeText(s, "語言包下載失敗（可到 Tab 5 先下載離線語言包）", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun render(items: List<Item>, my: Int) {
        val ov = overlay ?: return
        if (my != gen || !active) return
        ov.removeAllViews()
        val loc = IntArray(2)
        ov.getLocationOnScreen(loc)
        val ctx = ov.context
        val scale = Prefs.int(K_FONT, 120) / 100f
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 7f, ctx.resources.displayMetrics).toInt()
        val (defBg, defFg) = defaultColors(ctx)
        for (it in items) {
            val r = it.rect
            val tv = TextView(ctx).apply {
                text = it.out
                setTextColor(if (it.fg != 0) it.fg else defFg)
                background = GradientDrawable().apply {
                    setColor(if (it.bg != 0) it.bg else defBg)
                    cornerRadius = 4f
                }
                setPadding(2, 0, 2, 0)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            val lineH = r.height().toFloat() / max(1, it.lines)
            val maxPx = max(minPx + 2, min(r.height(), (lineH * 0.78f * scale).toInt()))
            tv.setAutoSizeTextTypeUniformWithConfiguration(minPx, maxPx, 1, TypedValue.COMPLEX_UNIT_PX)
            val lp = FrameLayout.LayoutParams(max(r.width(), 1), max(r.height(), 1))
            lp.leftMargin = r.left - loc[0]
            lp.topMargin = r.top - loc[1]
            ov.addView(tv, lp)
        }
        shownCount = items.size
        setStatus(if (items.isEmpty()) "待命" else "啟動")
    }

    // ---------------- 語言包管理（給 Tab 5 用） ----------------

    fun downloadedModels(cb: (Set<String>) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { set -> cb(set.map { it.language }.toSet()) }
            .addOnFailureListener { cb(emptySet()) }
    }

    fun downloadModel(code: String, cb: (Boolean) -> Unit) {
        val ml = Langs.mlkit(code) ?: return cb(false)
        RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(ml).build(), conditions)
            .addOnSuccessListener { cb(true) }
            .addOnFailureListener { cb(false) }
    }

    fun deleteModel(code: String, cb: (Boolean) -> Unit) {
        val ml = Langs.mlkit(code) ?: return cb(false)
        RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(ml).build())
            .addOnSuccessListener { cb(true) }
            .addOnFailureListener { cb(false) }
    }
}
