package tw.local.enhancer.feature.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.view.Display
import tw.local.enhancer.App
import tw.local.enhancer.core.EnhancerA11yService
import tw.local.enhancer.core.Shell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * 執行腳本：逐步做顏色判斷 → 送出手勢 → 等待。
 * 送出方式：Shizuku（input 指令）或無障礙手勢。
 */
class ClickerEngine(
    private val script: Script,
    private val onStatus: (String) -> Unit,
    private val beforeShot: () -> Unit,   // 截圖前把標記藏起來
    private val onDone: (String) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val session = Shell.Session()

    fun isRunning() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "clicker").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        try { Thread.sleep(ms) } catch (_: InterruptedException) { running = false }
    }

    private fun jitter(v: Int, j: Int) = if (j <= 0) v else v + Random.nextInt(-j, j + 1)

    private fun loop() {
        val start = System.currentTimeMillis()
        var round = 0
        var reason = "已停止"
        val useA11y = Scripts.injector() == "a11y"
        if (useA11y && EnhancerA11yService.instance == null) { finish("無障礙服務未開啟"); return }
        if (!useA11y && !Shell.ready()) { finish("Shizuku 未連線"); return }
        if (script.steps.isEmpty()) { finish("腳本沒有步驟"); return }
        try {
            outer@ while (running) {
                if (script.loops > 0 && round >= script.loops) { reason = "已完成 ${script.loops} 輪"; break }
                if (script.maxMin > 0 && System.currentTimeMillis() - start > script.maxMin * 60_000L) {
                    reason = "已達 ${script.maxMin} 分鐘上限"; break
                }
                round++
                for ((i, st) in script.steps.withIndex()) {
                    if (!running) break@outer
                    App.ui { onStatus("第 $round 輪｜步驟 ${i + 1}/${script.steps.size} ${st.typeName()}") }
                    if (st.cond && !conditionMet(st, i, round)) {
                        sleep(st.delay.toLong()); continue
                    }
                    perform(st, useA11y)
                    sleep(jitter(st.delay, script.jitterMs).coerceAtLeast(0).toLong())
                }
            }
        } catch (e: Exception) {
            reason = "錯誤：$e"
        }
        finish(reason)
    }

    private fun finish(reason: String) {
        running = false
        session.close()
        App.ui { onDone(reason) }
    }

    /** 顏色條件；onFail=wait 時一直等到符合或逾時 */
    private fun conditionMet(st: Step, i: Int, round: Int): Boolean {
        if (colorMatches(st)) return true
        if (st.onFail != Step.WAIT_COLOR) return false
        val deadline = System.currentTimeMillis() + st.waitMax
        while (running && System.currentTimeMillis() < deadline) {
            App.ui { onStatus("第 $round 輪｜步驟 ${i + 1} 等待顏色出現…") }
            sleep(400)
            if (colorMatches(st)) return true
        }
        return false
    }

    private fun colorMatches(st: Step): Boolean {
        val bmp = screenshot() ?: return false
        val c = sample(bmp, st.cx, st.cy)
        bmp.recycle()
        if (c == null) return false
        return abs(Color.red(c) - Color.red(st.color)) <= st.tol &&
            abs(Color.green(c) - Color.green(st.color)) <= st.tol &&
            abs(Color.blue(c) - Color.blue(st.color)) <= st.tol
    }

    private fun perform(st: Step, a11y: Boolean) {
        val jp = script.jitterPx
        val x = jitter(st.x, jp); val y = jitter(st.y, jp)
        when (st.type) {
            Step.WAIT -> {}
            Step.TAP -> if (a11y) gesture(x, y, x, y, 50) else session.run("input tap $x $y")
            Step.LONG -> if (a11y) gesture(x, y, x, y, st.dur) else session.run("input swipe $x $y $x $y ${st.dur}", st.dur + 5000L)
            Step.SWIPE -> {
                val x2 = jitter(st.x2, jp); val y2 = jitter(st.y2, jp)
                if (a11y) gesture(x, y, x2, y2, st.dur) else session.run("input swipe $x $y $x2 $y2 ${st.dur}", st.dur + 5000L)
            }
        }
    }

    private fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int) {
        val s = EnhancerA11yService.instance ?: return
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceAtLeast(1).toLong()))
            .build()
        val latch = CountDownLatch(1)
        App.ui {
            val ok = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription) { latch.countDown() }
                override fun onCancelled(d: GestureDescription) { latch.countDown() }
            }, null)
            if (!ok) latch.countDown()
        }
        latch.await(dur + 2000L, TimeUnit.MILLISECONDS)
    }

    // ---------------- 截圖取色 ----------------

    private fun screenshot(): Bitmap? {
        App.ui { beforeShot() }
        sleep(60)
        return Screen.capture()
    }

    companion object {
        /** 取 3×3 平均色 */
        fun sample(bmp: Bitmap, x: Int, y: Int): Int? {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dx in -1..1) for (dy in -1..1) {
                val px = x + dx; val py = y + dy
                if (px in 0 until bmp.width && py in 0 until bmp.height) {
                    val c = bmp.getPixel(px, py); r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                }
            }
            return if (n == 0) null else Color.rgb(r / n, g / n, b / n)
        }
    }
}

/** 截圖：Android 11+ 優先用無障礙截圖（快），否則用 Shizuku screencap */
object Screen {
    fun capture(): Bitmap? {
        val s = EnhancerA11yService.instance
        if (s != null && Build.VERSION.SDK_INT >= 30) {
            for (attempt in 0 until 3) {
                val r = a11yShot(s)
                if (r.first != null) return r.first
                if (!r.second) break   // 不是頻率限制造成的失敗，改用 Shizuku
                Thread.sleep(350)
            }
        }
        if (Shell.ready()) {
            val bytes = Shell.execBytes("screencap -p") ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        return null
    }

    /** 回傳（圖, 是否因頻率限制失敗） */
    private fun a11yShot(s: AccessibilityService): Pair<Bitmap?, Boolean> {
        if (Build.VERSION.SDK_INT < 30) return null to false
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        var tooFast = false
        App.ui {
            runCatching {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        val hb = r.hardwareBuffer
                        out = runCatching { Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                        hb.close()
                        latch.countDown()
                    }
                    override fun onFailure(code: Int) {
                        tooFast = code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                        latch.countDown()
                    }
                })
            }.onFailure { latch.countDown() }
        }
        latch.await(3, TimeUnit.SECONDS)
        return out to tooFast
    }
}
