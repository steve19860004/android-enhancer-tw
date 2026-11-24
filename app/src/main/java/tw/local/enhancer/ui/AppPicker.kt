package tw.local.enhancer.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import tw.local.enhancer.App
import tw.local.enhancer.core.Apps

/** 帶圖示與搜尋的 App 選擇器。multi=false 時點一下就選定。 */
object AppPicker {
    fun show(
        act: Activity,
        title: String,
        selected: Set<String>,
        multi: Boolean = true,
        includeSystem: Boolean = true,
        extra: List<String> = emptyList(),
        onDone: (Set<String>) -> Unit
    ) {
        val ui = Ui(act)
        val chosen = selected.toMutableSet()
        val root = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        val search = EditText(act).apply { hint = "搜尋 App 名稱或套件名稱…"; isSingleLine = true }
        root.addView(search)
        val progress = ProgressBar(act)
        root.addView(progress)
        val list = ListView(act)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(420)))

        var all: List<Apps.Info> = emptyList()
        var shown: List<Apps.Info> = emptyList()
        var dialog: AlertDialog? = null

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val row = (convert as? LinearLayout) ?: LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ui.dp(6), 0, ui.dp(6))
                    addView(ImageView(act), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
                    val texts = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(ui.dp(12), 0, ui.dp(8), 0)
                        addView(TextView(act).apply { textSize = 14.5f; setTextColor(ui.textMain) })
                        addView(TextView(act).apply { textSize = 11f; setTextColor(ui.textSub) })
                    }
                    addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(CheckBox(act).apply { isClickable = false; isFocusable = false })
                }
                val it = shown[p]
                (row.getChildAt(0) as ImageView).setImageDrawable(Apps.icon(act, it.pkg))
                val texts = row.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = it.label
                (texts.getChildAt(1) as TextView).text = it.pkg
                val cb = row.getChildAt(2) as CheckBox
                cb.visibility = if (multi) View.VISIBLE else View.GONE
                cb.isChecked = it.pkg in chosen
                return row
            }
        }
        list.adapter = adapter

        fun filter() {
            val q = search.text.toString().trim().lowercase()
            val base = all.sortedByDescending { it.pkg in chosen }
            shown = if (q.isEmpty()) base else base.filter { q in it.label.lowercase() || q in it.pkg.lowercase() }
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })
        list.setOnItemClickListener { _, _, p, _ ->
            val pkg = shown[p].pkg
            if (multi) {
                if (!chosen.add(pkg)) chosen.remove(pkg)
                adapter.notifyDataSetChanged()
            } else {
                dialog?.dismiss()
                onDone(setOf(pkg))
            }
        }

        val b = AlertDialog.Builder(act).setTitle(title).setView(root)
        if (multi) {
            b.setPositiveButton("確定") { _, _ -> onDone(chosen) }
            b.setNeutralButton("全部清除") { _, _ -> onDone(emptySet()) }
        }
        b.setNegativeButton("取消", null)
        dialog = b.show()

        App.bg {
            val apps = Apps.launchable(act, includeSystem).toMutableList()
            // 沒有桌面圖示但需要可選的套件（例如語音助理服務）
            val have = apps.map { it.pkg }.toSet()
            (extra + selected).filter { it !in have && Apps.installed(act, it) }
                .forEach { apps.add(Apps.Info(it, Apps.label(act, it), true)) }
            App.ui {
                all = apps
                progress.visibility = View.GONE
                filter()
            }
        }
    }
}
