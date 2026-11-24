package tw.local.enhancer.feature.switcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import tw.local.enhancer.core.Apps
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 全螢幕扇形面板。anchorX/anchorY 為螢幕座標；
 * 錨點在左半邊時往右展開，右半邊時往左展開。
 */
class FanView(
    ctx: Context,
    private val pkgs: List<String>,
    private val anchorX: Int,
    private val anchorY: Int,
    private val screenW: Int,
    private val screenH: Int,
    private val onPick: (String?) -> Unit
) : FrameLayout(ctx) {

    private fun dp(v: Float) = (v * resources.displayMetrics.density)

    init {
        setBackgroundColor(Color.argb(110, 0, 0, 0))
        build()
    }

    private fun build() {
        val icon = dp(50f).toInt()
        val cell = dp(74f).toInt()
        // 每一圈容量與半徑
        val rings = listOf(5 to dp(118f), 7 to dp(196f), 9 to dp(274f))
        val toRight = anchorX < screenW / 2
        val maxR = rings.last().second
        // 錨點太靠上下時，扇形中心往內移，避免圖示超出螢幕
        val cy = anchorY.toFloat().coerceIn(dp(90f), screenH - dp(90f))
        val cx = if (toRight) dp(8f) else screenW - dp(8f)
        val towardCenter = when {
            cy < maxR * 0.55f -> 1   // 靠上：偏向下方
            cy > screenH - maxR * 0.55f -> -1  // 靠下：偏向上方
            else -> 0
        }

        var idx = 0
        var delay = 0L
        for ((cap, r) in rings) {
            if (idx >= pkgs.size) break
            val n = min(cap, pkgs.size - idx)
            val span = 150.0 // 度
            val shift = towardCenter * 35.0
            for (k in 0 until n) {
                val t = if (n == 1) 0.5 else k.toDouble() / (n - 1)
                // 往右展開時角度 -75°..+75°（0° = 正右方），往左則鏡像
                var deg = -span / 2 + t * span + shift
                if (!toRight) deg = 180 - deg
                val rad = deg * PI / 180
                val x = (cx + r * cos(rad)).toFloat()
                val y = (cy + r * sin(rad)).toFloat()
                val pkg = pkgs[idx++]
                val item = itemView(pkg, icon)
                val lp = LayoutParams(cell, LayoutParams.WRAP_CONTENT)
                lp.leftMargin = (x - cell / 2f).toInt().coerceIn(0, screenW - cell)
                lp.topMargin = (y - icon / 2f).toInt().coerceIn(0, screenH - icon - dp(20f).toInt())
                addView(item, lp)
                // 動畫：從錨點飛出
                item.translationX = cx - (lp.leftMargin + cell / 2f)
                item.translationY = cy - (lp.topMargin + icon / 2f)
                item.scaleX = 0.2f; item.scaleY = 0.2f; item.alpha = 0f
                item.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setStartDelay(delay).setDuration(220).setInterpolator(OvershootInterpolator(1.1f)).start()
                delay += 18
            }
        }

        if (pkgs.isEmpty()) {
            val tv = TextView(context).apply {
                text = "還沒有最近使用紀錄\n請先開啟無障礙服務或使用情況存取"
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
            }
            addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        // 中心關閉鈕
        val close = TextView(context).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E8682A")) }
            setOnClickListener { onPick(null) }
        }
        val cs = dp(46f).toInt()
        val clp = LayoutParams(cs, cs)
        clp.leftMargin = (cx - cs / 2f).toInt().coerceIn(0, screenW - cs)
        clp.topMargin = (cy - cs / 2f).toInt()
        addView(close, clp)
    }

    private fun itemView(pkg: String, icon: Int): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val iv = ImageView(context).apply { setImageDrawable(Apps.icon(context, pkg)) }
            addView(iv, LinearLayout.LayoutParams(icon, icon))
            val tv = TextView(context).apply {
                text = Apps.label(context, pkg)
                setTextColor(Color.WHITE)
                textSize = 10.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }
            addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { onPick(pkg) }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) onPick(null)
        return true
    }
}
