package tw.local.enhancer.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import tw.local.enhancer.core.Prefs

/** 純程式碼 UI 小工具（不依賴 AndroidX / XML layout）。 */
class Ui(val ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val accent = Color.parseColor("#E8682A")
    val cardBg = if (dark) Color.parseColor("#262626") else Color.parseColor("#FFFFFF")
    val pageBg = if (dark) Color.parseColor("#141414") else Color.parseColor("#F3F1EE")
    val textMain = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#1D1D1D")
    val textSub = if (dark) Color.parseColor("#A8A8A8") else Color.parseColor("#666666")
    val okColor = Color.parseColor("#2E9E5B")
    val badColor = Color.parseColor("#D04545")

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun round(color: Int, radiusDp: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun vbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun card(parent: LinearLayout, title: String? = null, build: LinearLayout.() -> Unit): LinearLayout {
        val c = vbox().apply {
            background = round(cardBg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(12))
        parent.addView(c, lp)
        if (title != null) c.title(title)
        c.build()
        return c
    }

    fun LinearLayout.title(s: String): TextView = text(s, 16f, bold = true, color = accent).also {
        (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)
    }

    fun LinearLayout.text(s: CharSequence, size: Float = 14f, bold: Boolean = false, color: Int? = null): TextView {
        val t = TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color ?: textMain)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(2); lp.bottomMargin = dp(2)
        addView(t, lp)
        return t
    }

    fun LinearLayout.hint(s: CharSequence): TextView = text(s, 12.5f, color = textSub)

    fun LinearLayout.space(h: Int = 8) {
        addView(View(ctx), LinearLayout.LayoutParams(1, dp(h)))
    }

    fun LinearLayout.toggle(
        label: String, key: String, def: Boolean = false, desc: String? = null,
        onChange: ((Boolean) -> Unit)? = null
    ): Switch {
        val sw = Switch(ctx).apply {
            text = label
            textSize = 14.5f
            setTextColor(textMain)
            isChecked = Prefs.bool(key, def)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, v ->
                Prefs.put(key, v)
                onChange?.invoke(v)
            }
        }
        addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (desc != null) hint(desc)
        return sw
    }

    fun LinearLayout.button(label: String, primary: Boolean = false, onClick: (Button) -> Unit): Button {
        val b = Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else textMain)
            background = round(if (primary) accent else (if (dark) Color.parseColor("#353535") else Color.parseColor("#EDE8E3")), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(44)
            stateListAnimator = null
            setOnClickListener { onClick(this) }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(5); lp.bottomMargin = dp(5)
        addView(b, lp)
        return b
    }

    fun LinearLayout.seek(
        key: String, min: Int, max: Int, def: Int, label: (Int) -> String,
        onChange: ((Int) -> Unit)? = null
    ) {
        val t = text(label(Prefs.int(key, def)))
        val sb = SeekBar(ctx).apply {
            this.max = max - min
            progress = Prefs.int(key, def) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    t.text = label(p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    Prefs.put(key, s.progress + min)
                    onChange?.invoke(s.progress + min)
                }
            })
        }
        addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun LinearLayout.radios(key: String, def: String, options: List<Pair<String, String>>, onChange: ((String) -> Unit)? = null) {
        val g = RadioGroup(ctx)
        val cur = Prefs.str(key, def)
        options.forEachIndexed { idx, (value, label) ->
            val rb = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(textMain)
                isChecked = value == cur
                tag = value
                setPadding(dp(4), dp(6), 0, dp(6))
            }
            g.addView(rb)
            if (idx == 0 && options.none { it.first == cur }) rb.isChecked = true
        }
        g.setOnCheckedChangeListener { grp, id ->
            val v = grp.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            Prefs.put(key, v)
            onChange?.invoke(v)
        }
        addView(g)
    }

    /** 權限狀態列：●已授權 / ●未授權 ＋ 前往 */
    fun LinearLayout.status(label: String, ok: Boolean, onClick: (() -> Unit)?) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val dot = TextView(ctx).apply {
            text = if (ok) "● 已授權" else "● 未授權"
            setTextColor(if (ok) okColor else badColor)
            textSize = 12.5f
        }
        val t = TextView(ctx).apply { text = label; setTextColor(textMain); textSize = 14f }
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(dot)
        if (onClick != null && !ok) {
            row.setOnClickListener { onClick() }
            val go = TextView(ctx).apply { text = "  前往 ›"; setTextColor(accent); textSize = 13f }
            row.addView(go)
        }
        addView(row)
    }
}
