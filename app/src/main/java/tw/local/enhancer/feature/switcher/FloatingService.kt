package tw.local.enhancer.feature.switcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import tw.local.enhancer.App
import tw.local.enhancer.core.Apps
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.ui.MainActivity
import kotlin.math.abs

/** 懸浮球＋側邊欄＋扇形面板。 */
class FloatingService : Service() {

    companion object {
        const val K_ENABLED = "sw_enabled"
        const val K_BALL = "sw_ball"
        const val K_BALL_ALPHA = "sw_ball_alpha"
        const val K_EDGE_HIDE = "sw_edge_hide"
        const val K_SIDEBAR = "sw_sidebar"
        const val K_SIDEBAR_RIGHT = "sw_sidebar_right"
        const val K_SIDEBAR_THICK = "sw_sidebar_thick"
        const val K_SIDEBAR_LEN = "sw_sidebar_len"
        const val K_SIDEBAR_ALPHA = "sw_sidebar_alpha"
        const val K_MAX = "sw_max"
        private const val K_BALL_X = "sw_ball_x"
        private const val K_BALL_Y = "sw_ball_y"

        const val ACTION_EXPAND = "tw.local.enhancer.EXPAND"
        const val ACTION_STOP = "tw.local.enhancer.STOP"
        const val ACTION_REFRESH = "tw.local.enhancer.REFRESH"

        @Volatile var running = false
            private set

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, FloatingService::class.java)
            if (action != null) i.action = action
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingService::class.java))
        }

        fun refresh(ctx: Context) { if (running) start(ctx, ACTION_REFRESH) }

        fun screenSize(ctx: Context): Point {
            val wm = ctx.getSystemService(WindowManager::class.java)
            return if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.currentWindowMetrics.bounds
                Point(b.width(), b.height())
            } else {
                val p = Point()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
                p
            }
        }
    }

    private lateinit var wm: WindowManager
    private var ball: View? = null
    private var ballLp: WindowManager.LayoutParams? = null
    private var sidebar: View? = null
    private var fan: View? = null
    private val hideRunnable = Runnable { edgeHide() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        running = true
        goForeground()
    }

    private fun goForeground() {
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, App.CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("扇形切換器執行中")
            .setContentText("點懸浮球或滑側邊欄展開最近開啟的 App")
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "停止", stopPi).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(11, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.put(K_ENABLED, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXPAND -> {
                if (ball == null && sidebar == null) setupViews()
                // 等下拉面板收起、跳板 Activity 關閉後再展開
                App.main.postDelayed({
                    val s = screenSize(this)
                    val lp = ballLp
                    if (lp != null && ball != null) showFan(lp.x + dp(26), lp.y + dp(26))
                    else showFan(if (Prefs.bool(K_SIDEBAR_RIGHT, true)) s.x else 0, s.y / 2)
                }, 250)
            }
            else -> setupViews()
        }
        return START_STICKY
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun setupViews() {
        removeAll()
        if (Prefs.bool(K_BALL, true)) addBall()
        if (Prefs.bool(K_SIDEBAR, false)) addSidebar()
    }

    private fun removeAll() {
        App.main.removeCallbacks(hideRunnable)
        listOf(ball, sidebar, fan).forEach { v -> if (v != null) runCatching { wm.removeView(v) } }
        ball = null; sidebar = null; fan = null; ballLp = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBall() {
        val size = dp(52)
        val outer = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E8682A")) }
        val inner = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(3), Color.WHITE) }
        val layer = LayerDrawable(arrayOf(outer, inner)).apply { setLayerInset(1, dp(14), dp(14), dp(14), dp(14)) }
        val v = View(this).apply {
            background = layer
            alpha = Prefs.int(K_BALL_ALPHA, 85) / 100f
            elevation = dp(4).toFloat()
        }
        val s = screenSize(this)
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.int(K_BALL_X, s.x - size - dp(4)).coerceIn(-size / 2, s.x - size / 2)
            y = Prefs.int(K_BALL_Y, s.y / 3).coerceIn(0, s.y - size)
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longPressed = false
        val longPress = Runnable {
            longPressed = true
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    unHide()
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; longPressed = false
                    App.main.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        moved = true; App.main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    App.main.removeCallbacks(longPress)
                    if (moved) {
                        snapToEdge(view, lp)
                    } else if (!longPressed && e.action == MotionEvent.ACTION_UP) {
                        showFan(lp.x + size / 2, lp.y + size / 2)
                    }
                    scheduleHide()
                }
            }
            true
        }
        wm.addView(v, lp)
        ball = v; ballLp = lp
        scheduleHide()
    }

    private fun snapToEdge(view: View, lp: WindowManager.LayoutParams) {
        val s = screenSize(this)
        val size = view.width.takeIf { it > 0 } ?: dp(52)
        lp.x = if (lp.x + size / 2 < s.x / 2) 0 else s.x - size
        lp.y = lp.y.coerceIn(0, s.y - size)
        runCatching { wm.updateViewLayout(view, lp) }
        Prefs.put(K_BALL_X, lp.x); Prefs.put(K_BALL_Y, lp.y)
    }

    private fun scheduleHide() {
        App.main.removeCallbacks(hideRunnable)
        if (Prefs.bool(K_EDGE_HIDE, true)) App.main.postDelayed(hideRunnable, 3000)
    }

    private fun edgeHide() {
        val v = ball ?: return
        val lp = ballLp ?: return
        val s = screenSize(this)
        val size = v.width.takeIf { it > 0 } ?: dp(52)
        lp.x = if (lp.x + size / 2 < s.x / 2) -size / 2 else s.x - size / 2
        runCatching { wm.updateViewLayout(v, lp) }
        v.alpha = 0.4f
    }

    private fun unHide() {
        App.main.removeCallbacks(hideRunnable)
        val v = ball ?: return
        val lp = ballLp ?: return
        val s = screenSize(this)
        val size = v.width.takeIf { it > 0 } ?: dp(52)
        if (lp.x < 0) lp.x = 0
        if (lp.x > s.x - size) lp.x = s.x - size
        runCatching { wm.updateViewLayout(v, lp) }
        v.alpha = Prefs.int(K_BALL_ALPHA, 85) / 100f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addSidebar() {
        val right = Prefs.bool(K_SIDEBAR_RIGHT, true)
        val thick = dp(Prefs.int(K_SIDEBAR_THICK, 14))
        val len = dp(Prefs.int(K_SIDEBAR_LEN, 160))
        val v = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E8682A"))
                cornerRadius = dp(6).toFloat()
            }
            alpha = Prefs.int(K_SIDEBAR_ALPHA, 35) / 100f
        }
        val s = screenSize(this)
        val lp = WindowManager.LayoutParams(
            thick, len, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (right) Gravity.END else Gravity.START) or Gravity.TOP
            x = 0
            y = (s.y * 0.38f).toInt()
        }
        var downX = 0f; var downY = 0f; var fired = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> {
                    val inward = if (right) downX - e.rawX else e.rawX - downX
                    if (!fired && inward > dp(24)) {
                        fired = true
                        showFan(if (right) s.x else 0, e.rawY.toInt())
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!fired && abs(e.rawX - downX) < dp(10) && abs(e.rawY - downY) < dp(10)) {
                        showFan(if (right) s.x else 0, e.rawY.toInt())
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        sidebar = v
    }

    private fun showFan(ax: Int, ay: Int) {
        fan?.let { runCatching { wm.removeView(it) } }
        fan = null
        val s = screenSize(this)
        val pkgs = RecentTracker.list(this, Prefs.int(K_MAX, 8))
        val v = FanView(this, pkgs, ax, ay, s.x, s.y) { pkg ->
            dismissFan()
            if (pkg != null) Apps.launch(this, pkg)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { wm.addView(v, lp); fan = v }
    }

    private fun dismissFan() {
        fan?.let { runCatching { wm.removeView(it) } }
        fan = null
    }

    override fun onDestroy() {
        removeAll()
        running = false
        super.onDestroy()
    }
}
