package tw.local.enhancer.feature.volume

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import tw.local.enhancer.App
import tw.local.enhancer.core.Perms
import tw.local.enhancer.core.Prefs

/**
 * 免 root 音量增大：用 LoudnessEnhancer 掛到全域輸出（session 0），把媒體音量推到 100% 以上。
 * 搭配無障礙服務攔截音量鍵：音量已最大時再按音量＋，就自動把增益往上加。
 */
object VolumeBooster {
    const val K_ENABLED = "vol_enabled"      // 增益是否生效
    const val K_KEYHOOK = "vol_keyhook"      // 攔截音量鍵自動增大
    private const val K_GAIN = "vol_gain_mb" // 目前增益（毫貝 mB）
    const val MAX_MB = 1500                  // 上限 +15 dB
    const val STEP_MB = 100                  // 每階 +1 dB
    const val GLOBAL_SESSION = 0

    @Volatile private var enhancer: LoudnessEnhancer? = null
    @Volatile var lastError: String = ""
        private set

    fun gainMb() = Prefs.int(K_GAIN, 0).coerceIn(0, MAX_MB)
    fun gainDb() = gainMb() / 100f
    fun enabled() = Prefs.bool(K_ENABLED)

    /** 開啟增益（建立並掛上效果），回傳是否成功 */
    @Synchronized
    fun enable(): Boolean {
        if (enhancer == null) {
            enhancer = try {
                LoudnessEnhancer(GLOBAL_SESSION)
            } catch (e: Throwable) {
                lastError = e.message ?: e.toString()
                null
            }
        }
        val e = enhancer ?: return false
        return try {
            e.setTargetGain(gainMb())
            e.enabled = true
            Prefs.put(K_ENABLED, true)
            lastError = ""
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
            false
        }
    }

    @Synchronized
    fun disable() {
        Prefs.put(K_ENABLED, false)
        runCatching { enhancer?.enabled = false }
        runCatching { enhancer?.release() }
        enhancer = null
    }

    /** 設定增益（mB），同步套到效果上 */
    @Synchronized
    fun setGain(mb: Int) {
        val v = mb.coerceIn(0, MAX_MB)
        Prefs.put(K_GAIN, v)
        if (enabled()) {
            if (enhancer == null) enable() else runCatching { enhancer?.setTargetGain(v); enhancer?.enabled = true }
        }
    }

    /** 加減一個量（音量鍵或按鈕用），回傳新的 dB；會顯示 HUD */
    fun bump(ctx: Context, deltaMb: Int): Float {
        if (!enabled()) enable()
        setGain(gainMb() + deltaMb)
        showHud(ctx)
        return gainDb()
    }

    /** App 啟動時：若之前開著，重新掛上 */
    fun restoreIfEnabled() {
        if (enabled()) enable()
    }

    fun mediaAtMax(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) >= am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    // ---------------- HUD（短暫浮層顯示目前增益） ----------------
    private var hud: View? = null
    private val hideHud = Runnable {
        val v = hud ?: return@Runnable
        runCatching { App.app.getSystemService(WindowManager::class.java).removeView(v) }
        hud = null
    }

    private fun showHud(ctx: Context) {
        if (!Perms.overlay(ctx)) {
            App.main.removeCallbacks(hideHud)
            Toast.makeText(ctx, "音量增大 +%.1f dB".format(gainDb()), Toast.LENGTH_SHORT).show()
            return
        }
        App.main.removeCallbacks(hideHud)
        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        if (hud == null) {
            val wm = ctx.getSystemService(WindowManager::class.java)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(Color.argb(225, 20, 20, 20)); cornerRadius = d(16).toFloat() }
                setPadding(d(22), d(16), d(22), d(16))
            }
            box.addView(TextView(ctx).apply { tag = "label"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER })
            val track = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { setColor(Color.parseColor("#44FFFFFF")); cornerRadius = d(3).toFloat() }
            }
            val fill = View(ctx).apply { tag = "fill"; background = GradientDrawable().apply { setColor(Color.parseColor("#E8682A")); cornerRadius = d(3).toFloat() } }
            track.addView(fill, FrameLayout.LayoutParams(0, d(6)))
            box.addView(track, LinearLayout.LayoutParams(d(180), d(6)).apply { topMargin = d(10) })
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER; }
            runCatching { wm.addView(box, lp); hud = box }
        }
        val box = hud as? LinearLayout ?: return
        (box.getChildAt(0) as? TextView)?.text = "🔊 音量增大 +%.1f dB".format(gainDb())
        val track = box.getChildAt(1) as? FrameLayout
        val fill = track?.getChildAt(0)
        fill?.let {
            val frac = gainMb().toFloat() / MAX_MB
            it.layoutParams = FrameLayout.LayoutParams((d(180) * frac).toInt().coerceAtLeast(d(2)), d(6))
            it.requestLayout()
        }
        App.main.postDelayed(hideHud, 1100)
    }
}
