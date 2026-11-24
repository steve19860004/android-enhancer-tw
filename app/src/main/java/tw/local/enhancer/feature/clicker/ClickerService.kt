package tw.local.enhancer.feature.clicker

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import tw.local.enhancer.App
import kotlin.math.abs

/**
 * 自動點擊的懸浮編輯器＋執行器。
 * 面板：＋點擊／＋長按／＋滑動／＋等待／☰ 清單／▶ 執行／💾 儲存／✕ 關閉。
 * 每個步驟在畫面上有一個編號圓點，拖曳調位置、點一下改設定。
 */
class ClickerService : Service() {

    companion object {
        const val EXTRA_ID = "script_id"
        const val ACTION_STOP = "tw.local.enhancer.clicker.STOP"
        @Volatile var running = false
            private set

        fun open(ctx: Context, scriptId: String) {
            val i = Intent(ctx, ClickerService::class.java).putExtra(EXTRA_ID, scriptId)
            ctx.startForegroundService(i)
        }
    }

    private lateinit var wm: WindowManager
    private var script = Script()
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var playBtn: TextView? = null
    private val markers = mutableListOf<View>()
    private var engine: ClickerEngine? = null
    private var dirty = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        running = true
        val stopPi = PendingIntent.getService(
            this, 3, Intent(this, ClickerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, App.CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("自動點擊")
            .setContentText("懸浮編輯器開啟中")
            .addAction(Notification.Action.Builder(null, "關閉", stopPi).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(12, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(12, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { closeAll(); return START_NOT_STICKY }
        val id = intent?.getStringExtra(EXTRA_ID)
        if (id != null) {
            engine?.stop()
            script = Scripts.get(id) ?: Script(id = id)
            dirty = false
            removeOverlays()
            addPanel()
            rebuildMarkers()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        removeOverlays()
        running = false
        super.onDestroy()
    }

    private fun closeAll() {
        engine?.stop()
        if (dirty) Scripts.save(script)
        removeOverlays()
        stopSelf()
    }

    private fun removeOverlays() {
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun params(w: Int, h: Int, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun screen() = resources.displayMetrics

    // ---------------- 面板 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addPanel() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.argb(235, 30, 30, 30)); cornerRadius = dp(14).toFloat() }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val title = TextView(this).apply {
            text = "≡ " + script.name
            setTextColor(Color.parseColor("#FF9A5C")); textSize = 12f
            setPadding(dp(6), dp(2), dp(6), dp(4))
        }
        bar.addView(title)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(row: LinearLayout, label: String, color: Int = Color.WHITE, onClick: () -> Unit): TextView {
            val t = TextView(this).apply {
                text = label; textSize = 13f; setTextColor(color)
                setPadding(dp(9), dp(7), dp(9), dp(7))
                setOnClickListener { onClick() }
            }
            row.addView(t); return t
        }
        btn(row1, "＋點") { addStep(Step.TAP) }
        btn(row1, "＋長按") { addStep(Step.LONG) }
        btn(row1, "＋滑") { addStep(Step.SWIPE) }
        btn(row1, "＋等") { addStep(Step.WAIT) }
        playBtn = btn(row2, "▶ 執行", Color.parseColor("#7CDB8A")) { togglePlay() }
        btn(row2, "☰") { listDialog() }
        btn(row2, "💾") { Scripts.save(script); dirty = false; toast("已儲存") }
        btn(row2, "✕", Color.parseColor("#FF6B6B")) { closeAll() }
        bar.addView(row1); bar.addView(row2)
        status = TextView(this).apply {
            text = "${script.steps.size} 個步驟"; textSize = 11f; setTextColor(Color.LTGRAY)
            setPadding(dp(6), dp(2), dp(6), 0)
        }
        bar.addView(status)

        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.x = dp(8); lp.y = screen().heightPixels / 4
        // 拖曳標題列移動面板
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    runCatching { wm.updateViewLayout(bar, lp) }
                }
            }
            true
        }
        wm.addView(bar, lp)
        panel = bar; panelLp = lp
    }

    private fun setStatus(s: String) { status?.text = s }

    // ---------------- 標記 ----------------

    private fun rebuildMarkers() {
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        script.steps.forEachIndexed { i, st ->
            if (!st.hasPoint()) return@forEachIndexed
            addMarker(i, st, false)
            if (st.type == Step.SWIPE) addMarker(i, st, true)
        }
        // 面板永遠在最上層
        panel?.let { p -> panelLp?.let { runCatching { wm.removeView(p); wm.addView(p, it) } } }
        setStatus("${script.steps.size} 個步驟")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addMarker(index: Int, st: Step, end: Boolean) {
        val size = dp(40)
        val color = when (st.type) {
            Step.LONG -> "#C77DFF"; Step.SWIPE -> if (end) "#4CC9F0" else "#4895EF"; Step.WAIT -> "#777777"; else -> "#E8682A"
        }
        val v = TextView(this).apply {
            text = "${index + 1}" + if (end) "'" else if (st.type == Step.WAIT) "⏱" else ""
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(color) and 0xCCFFFFFF.toInt())
                setStroke(dp(2), if (st.cond) Color.YELLOW else Color.WHITE)
            }
        }
        val lp = params(size, size)
        val px = if (end) st.x2 else st.x
        val py = if (end) st.y2 else st.y
        lp.x = px - size / 2; lp.y = py - size / 2
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > dp(4) || abs(e.rawY - sy) > dp(4)) moved = true
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    runCatching { wm.updateViewLayout(view, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // 用實際螢幕位置算中心，避開狀態列／瀏海偏移
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val cx = loc[0] + size / 2; val cy = loc[1] + size / 2
                        if (end) { st.x2 = cx; st.y2 = cy } else {
                            if (st.cond && st.cx == st.x && st.cy == st.y) { st.cx = cx; st.cy = cy }
                            st.x = cx; st.y = cy
                        }
                        dirty = true
                    } else editDialog(index)
                }
            }
            true
        }
        wm.addView(v, lp)
        markers.add(v)
    }

    /** 執行時圓點要隱藏「並且」不接收觸控，否則點擊會打在圓點視窗上而不是遊戲 */
    private fun setMarkersVisible(visible: Boolean) {
        for (m in markers) {
            m.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            val lp = m.layoutParams as? WindowManager.LayoutParams ?: continue
            lp.flags = if (visible) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(m, lp) }
        }
    }

    private fun addStep(type: String) {
        val m = screen()
        val cx = m.widthPixels / 2; val cy = m.heightPixels / 2
        val st = Step(type = type, x = cx, y = cy, x2 = cx, y2 = cy - dp(200), cx = cx, cy = cy)
        st.dur = when (type) { Step.LONG -> 800; Step.SWIPE -> 300; else -> 60 }
        st.delay = if (type == Step.WAIT) 1000 else 500
        script.steps.add(st)
        dirty = true
        rebuildMarkers()
        toast(if (type == Step.WAIT) "已新增等待步驟；要等某個畫面出現，就把灰色圓點拖到那裡並取色"
            else "已新增第 ${script.steps.size} 步，拖曳圓點到要點的位置")
    }

    // ---------------- 執行 ----------------

    private fun togglePlay() {
        engine?.let { if (it.isRunning()) { it.stop(); return } }
        if (script.steps.isEmpty()) { toast("先新增步驟"); return }
        Scripts.save(script); dirty = false
        setMarkersVisible(false)
        playBtn?.text = "■ 停止"
        playBtn?.setTextColor(Color.parseColor("#FF6B6B"))
        val e = ClickerEngine(script, { setStatus(it) }, { setMarkersVisible(false) }) { reason ->
            playBtn?.text = "▶ 執行"
            playBtn?.setTextColor(Color.parseColor("#7CDB8A"))
            setMarkersVisible(true)
            setStatus(reason)
        }
        engine = e
        e.start()
    }

    // ---------------- 對話框 ----------------

    private fun overlayDialog(b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        d.show()
        return d
    }

    private fun builder() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun listDialog() {
        if (script.steps.isEmpty()) { toast("還沒有步驟"); return }
        val items = script.steps.mapIndexed { i, s ->
            "${i + 1}. ${s.typeName()}" + (if (s.hasPoint()) " (${s.x},${s.y})" else "") +
                "  等 ${s.delay}ms" + if (s.cond) "  🎨" else ""
        }.toTypedArray()
        overlayDialog(builder().setTitle("步驟清單（點一下編輯）").setItems(items) { _, w -> editDialog(w) }
            .setNegativeButton("關閉", null))
    }

    private fun num(parent: LinearLayout, label: String, value: Int): EditText {
        parent.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, dp(8), 0, 0) })
        val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(value.toString()); textSize = 15f }
        parent.addView(e)
        return e
    }

    private fun editDialog(index: Int) {
        val st = script.steps.getOrNull(index) ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val delay = num(box, "這一步做完後等待（毫秒）", st.delay)
        val dur = if (st.type == Step.LONG || st.type == Step.SWIPE) num(box, if (st.type == Step.LONG) "按住時間（毫秒）" else "滑動時間（毫秒）", st.dur) else null

        val cond = CheckBox(this).apply {
            text = if (st.type == Step.WAIT) "等到某個顏色出現才繼續" else "只有顏色符合時才執行"
            isChecked = st.cond
        }
        box.addView(cond)
        val colorInfo = TextView(this).apply { textSize = 12.5f }
        fun showColor() {
            colorInfo.text = if (st.color == 0) "尚未取色" else
                "取色位置 (${st.cx}, ${st.cy})　顏色 #%06X".format(st.color and 0xFFFFFF)
            colorInfo.setTextColor(if (st.color == 0) Color.GRAY else st.color or 0xFF000000.toInt())
        }
        showColor()
        box.addView(colorInfo)
        val pick = TextView(this).apply {
            text = "🎨 在這個圓點的位置取色（會先藏起圓點）"
            setTextColor(Color.parseColor("#E8682A")); textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        box.addView(pick)
        val tol = num(box, "顏色容許誤差（0–255，越大越寬鬆）", st.tol)
        val rg = RadioGroup(this)
        val rSkip = RadioButton(this).apply { text = "不符合就跳過這一步"; id = View.generateViewId() }
        val rWait = RadioButton(this).apply { text = "一直等到符合"; id = View.generateViewId() }
        rg.addView(rSkip); rg.addView(rWait)
        rg.check(if (st.onFail == Step.WAIT_COLOR || st.type == Step.WAIT) rWait.id else rSkip.id)
        box.addView(rg)
        val waitMax = num(box, "最多等多久（毫秒）", st.waitMax)

        val scroll = ScrollView(this).apply { addView(box) }
        lateinit var d: AlertDialog
        pick.setOnClickListener {
            if (!st.hasPoint()) return@setOnClickListener
            st.cx = st.x; st.cy = st.y
            d.window?.decorView?.visibility = View.INVISIBLE
            setMarkersVisible(false)
            App.bg {
                Thread.sleep(250)
                val bmp = Screen.capture()
                val c = bmp?.let { ClickerEngine.sample(it, st.cx, st.cy) }
                bmp?.recycle()
                App.ui {
                    setMarkersVisible(true)
                    d.window?.decorView?.visibility = View.VISIBLE
                    if (c == null) toast("取色失敗：需要無障礙（Android 11+）或 Shizuku；部分遊戲禁止截圖")
                    else { st.color = c; cond.isChecked = true; dirty = true; showColor() }
                }
            }
        }
        d = overlayDialog(builder().setTitle("第 ${index + 1} 步：${st.typeName()}").setView(scroll)
            .setPositiveButton("確定") { _, _ ->
                st.delay = delay.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: st.delay
                dur?.let { st.dur = it.text.toString().toIntOrNull()?.coerceIn(1, 60000) ?: st.dur }
                st.cond = cond.isChecked && st.color != 0
                st.tol = tol.text.toString().toIntOrNull()?.coerceIn(0, 255) ?: st.tol
                st.onFail = if (rg.checkedRadioButtonId == rWait.id) Step.WAIT_COLOR else Step.SKIP
                st.waitMax = waitMax.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: st.waitMax
                dirty = true
                rebuildMarkers()
            }
            .setNeutralButton("刪除這步") { _, _ ->
                script.steps.removeAt(index); dirty = true; rebuildMarkers()
            }
            .setNegativeButton("取消", null))
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
