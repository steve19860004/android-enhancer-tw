package tw.local.enhancer.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import tw.local.enhancer.App
import tw.local.enhancer.core.Apps
import tw.local.enhancer.core.Perms
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell
import tw.local.enhancer.feature.clicker.ClickerService
import tw.local.enhancer.feature.clicker.Script
import tw.local.enhancer.feature.clicker.Scripts
import tw.local.enhancer.feature.sys.SysTools

/** Tab 7 系統工具、Tab 8 自動點擊 */
class SysPages(private val act: MainActivity, private val pages: Pages) {
    private val ui get() = act.ui

    // =========================== Tab 7 系統 ===========================

    fun system(root: LinearLayout) = with(ui) {
        if (!Shell.ready()) card(root, "需要 Shizuku") {
            hint("這一頁大部分功能需要 Shizuku。動畫速度與私人 DNS 只要有「寫入安全設定」權限也能用。")
            button("授權 Shizuku", primary = true) { pages.requestShizuku() }
        }
        card(root, "App 管理（凍結／移除／斷網／背景／匯出）") {
            hint("點 App 可以：停用（凍結）、只對本機使用者移除、單獨斷網、加入或移出背景保活白名單、限制背景執行、匯出 APK。")
            text("⚠ 停用或移除系統 App 前請確認用途；手機異常時可在這裡還原。移除只是對本機使用者隱藏，系統分割區的原檔還在，隨時能還原。", 12.5f, color = badColor)
            toggle("清單包含系統 App", "sys_show_system", true)
            button("開啟 App 清單", primary = true) { appList() }
            val removed = SysTools.removedByMe()
            if (removed.isNotEmpty()) {
                text("已移除（${removed.size}）", bold = true)
                for (p in removed) row(this, p, "還原") {
                    App.bg { val r = SysTools.restore(p); App.ui { act.toast(if (r.ok) "已還原" else "失敗：$r"); act.render() } }
                }
            }
            val blocked = SysTools.blocked()
            if (blocked.isNotEmpty()) {
                text("已斷網（${blocked.size}）", bold = true)
                for (p in blocked) row(this, p, "恢復網路") {
                    App.bg { val r = SysTools.setBlocked(p, false); App.ui { act.toast(if (r.ok) "已恢復" else "失敗：$r"); act.render() } }
                }
            }
        }
        card(root, "動畫速度") {
            val cur = SysTools.animScale(act)
            text("目前：${fmt(cur)} 倍", 13f, color = textSub)
            val opts = listOf(0f to "關閉（最快）", 0.5f to "0.5 倍（推薦，順又快）", 1f to "1 倍（原廠）", 1.5f to "1.5 倍")
            for ((v, label) in opts) button(label, primary = cur == v) {
                if (!SysTools.setAnimScale(act, v)) act.toast("需要 Shizuku 或寫入安全設定權限")
                act.render()
            }
        }
        card(root, "私人 DNS（全手機擋廣告，免 VPN）") {
            text("目前：" + SysTools.dnsText(act), 13f, color = textSub)
            for ((v, label) in SysTools.DNS_PRESETS) button(label) {
                if (!SysTools.setDns(act, v)) act.toast("需要 Shizuku 或寫入安全設定權限")
                else act.toast("已設定：$label")
                act.render()
            }
            button("自訂 DNS 主機名稱") {
                val et = EditText(act).apply { hint = "例：dns.adguard-dns.com"; isSingleLine = true }
                AlertDialog.Builder(act).setTitle("自訂私人 DNS").setView(et)
                    .setPositiveButton("設定") { _, _ ->
                        val h = et.text.toString().trim()
                        if (h.isNotEmpty() && SysTools.setDns(act, h)) act.toast("已設定：$h")
                        act.render()
                    }.setNegativeButton("取消", null).show()
            }
            hint("擋廣告型 DNS 偶爾會讓某些網站或 App 連不上，遇到時切回「自動」即可。")
        }
    }

    private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    private fun row(parent: LinearLayout, pkg: String, action: String, onClick: () -> Unit) = with(ui) {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(TextView(act).apply {
            text = Apps.label(act, pkg) + "\n" + pkg; textSize = 13f; setTextColor(textMain)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(act).apply {
            text = action; setTextColor(accent); textSize = 13.5f
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { onClick() }
        })
        parent.addView(r)
    }

    /** 可搜尋的全部 App 清單 */
    private fun appList() {
        val u = ui
        val rootV = u.vbox().apply { setPadding(u.dp(16), u.dp(8), u.dp(16), 0) }
        val search = EditText(act).apply { hint = "搜尋 App 名稱或套件名稱…"; isSingleLine = true }
        rootV.addView(search)
        val progress = ProgressBar(act)
        rootV.addView(progress)
        val list = ListView(act)
        rootV.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(440)))
        var all: List<SysTools.Pkg> = emptyList()
        var shown: List<SysTools.Pkg> = emptyList()
        var blocked: Set<String> = SysTools.blocked()
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val row = (convert as? LinearLayout) ?: LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, u.dp(6), 0, u.dp(6))
                    addView(ImageView(act), LinearLayout.LayoutParams(u.dp(36), u.dp(36)))
                    addView(LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(u.dp(12), 0, 0, 0)
                        addView(TextView(act).apply { textSize = 14.5f; setTextColor(u.textMain) })
                        addView(TextView(act).apply { textSize = 11f; setTextColor(u.textSub) })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                val it = shown[p]
                (row.getChildAt(0) as ImageView).setImageDrawable(Apps.icon(act, it.pkg))
                val texts = row.getChildAt(1) as LinearLayout
                val tags = buildList {
                    if (!it.installed) add("已移除")
                    if (it.installed && !it.enabled) add("已停用")
                    if (it.pkg in blocked) add("斷網")
                    if (it.system) add("系統")
                }
                (texts.getChildAt(0) as TextView).text = it.label + if (tags.isEmpty()) "" else "  [" + tags.joinToString("・") + "]"
                (texts.getChildAt(1) as TextView).text = it.pkg
                return row
            }
        }
        list.adapter = adapter
        fun filter() {
            val q = search.text.toString().trim().lowercase()
            shown = if (q.isEmpty()) all else all.filter { q in it.label.lowercase() || q in it.pkg.lowercase() }
            adapter.notifyDataSetChanged()
        }
        fun load() {
            progress.visibility = View.VISIBLE
            App.bg {
                val a = SysTools.allPackages(act, Prefs.bool("sys_show_system", true))
                App.ui { all = a; blocked = SysTools.blocked(); progress.visibility = View.GONE; filter() }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })
        list.setOnItemClickListener { _, _, p, _ -> appActions(shown[p]) { load() } }
        AlertDialog.Builder(act).setTitle("App 管理").setView(rootV)
            .setNegativeButton("關閉") { _, _ -> act.render() }.show()
        load()
    }

    private fun appActions(p: SysTools.Pkg, reload: () -> Unit) {
        if (!Shell.ready()) { pages.requestShizuku(); return }
        App.bg {
            val wl = SysTools.whitelist().contains(p.pkg)
            val restricted = SysTools.bgRestricted(p.pkg)
            App.ui {
                val blocked = p.pkg in SysTools.blocked()
                val actions = mutableListOf<Pair<String, () -> Any>>()
                if (!p.installed) actions += "還原（重新安裝給本機使用者）" to { SysTools.restore(p.pkg) }
                else {
                    actions += (if (p.enabled) "停用（凍結）" else "啟用（解凍）") to { if (p.enabled) SysTools.disable(p.pkg) else SysTools.enable(p.pkg) }
                    actions += "對本機使用者移除" to { SysTools.uninstallForUser(p.pkg) }
                    if (SysTools.firewallSupported()) actions += (if (blocked) "恢復網路" else "斷網（禁止連線）") to { SysTools.setBlocked(p.pkg, !blocked) }
                    actions += (if (wl) "移出背景保活白名單" else "加入背景保活白名單（不被省電殺掉）") to { SysTools.setWhitelist(p.pkg, !wl) }
                    actions += (if (restricted) "解除背景限制" else "限制背景執行（省電）") to { SysTools.setBgRestricted(p.pkg, !restricted) }
                    actions += "匯出 APK" to { App.ui { act.exportApk(p.pkg) }; Unit }
                    actions += "開啟應用程式資訊" to {
                        App.ui { runCatching { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + p.pkg))) } }; Unit
                    }
                }
                AlertDialog.Builder(act).setTitle(p.label)
                    .setItems(actions.map { it.first }.toTypedArray()) { _, w ->
                        val (label, fn) = actions[w]
                        val run = {
                            App.bg {
                                val r = fn()
                                App.ui {
                                    if (r is Shell.Result) act.toast(if (r.ok || r.out.contains("Success")) "完成：$label" else "失敗：$r")
                                    reload()
                                }
                            }
                        }
                        if (label.startsWith("停用") || label.startsWith("對本機使用者移除")) {
                            AlertDialog.Builder(act).setTitle("確定要$label「${p.label}」？")
                                .setMessage(if (p.system) "這是系統 App，停用或移除可能影響手機功能。可以隨時在這裡還原。" else "可以隨時在這裡還原。")
                                .setPositiveButton("確定") { _, _ -> run() }.setNegativeButton("取消", null).show()
                        } else run()
                    }.show()
            }
        }
    }

    // =========================== Tab 8 自動點擊 ===========================

    fun clicker(root: LinearLayout) = with(ui) {
        val useA11y = Scripts.injector() == "a11y"
        val missing = buildList {
            if (!Perms.overlay(act)) add("overlay")
            if (useA11y && !Perms.a11y(act)) add("a11y")
            if (!useA11y && !Shell.ready()) add("shizuku")
        }
        if (missing.isNotEmpty()) card(root, "需要先授權") {
            if ("overlay" in missing) status("顯示在其他應用程式上層", false) { Perms.openOverlay(act) }
            if ("a11y" in missing) status("無障礙服務", false) { Perms.openA11y(act) }
            if ("shizuku" in missing) status("Shizuku", false) { pages.requestShizuku() }
        }
        card(root, "自動點擊／掛機腳本") {
            hint("在畫面上放編號圓點設定點擊、長按、滑動與等待，可設「某個位置變成某顏色才點」來掛機（例如等按鈕出現再按）。")
            text("⚠ 部分遊戲（尤其線上對戰、排行榜）會偵測自動點擊，可能被封號，請自行評估。", 12.5f, color = badColor)
            text("點擊方式", bold = true)
            radios(Scripts.K_INJECTOR, "shizuku", listOf(
                "shizuku" to "Shizuku 指令（遊戲較難偵測；每下約 0.1–0.3 秒延遲）",
                "a11y" to "無障礙手勢（不需 Shizuku、較快；有些遊戲會偵測無障礙）"
            )) { act.render() }
            hint("顏色判斷需要截圖：Android 11+ 用無障礙截圖，否則用 Shizuku。禁止截圖的遊戲無法用顏色判斷。")
        }
        card(root, "我的腳本") {
            val list = Scripts.all()
            if (list.isEmpty()) hint("還沒有腳本，按下面新增。")
            for (s in list) {
                text(s.name, bold = true)
                hint("${s.steps.size} 個步驟｜" + (if (s.loops == 0) "無限循環" else "重複 ${s.loops} 輪") +
                    (if (s.maxMin > 0) "｜最多 ${s.maxMin} 分鐘" else "") +
                    (if (s.jitterPx > 0 || s.jitterMs > 0) "｜隨機偏移 ${s.jitterPx}px／${s.jitterMs}ms" else ""))
                val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                fun small(label: String, color: Int = accent, f: () -> Unit) {
                    r.addView(TextView(act).apply {
                        text = label; setTextColor(color); textSize = 14f
                        setPadding(0, dp(4), dp(20), dp(12))
                        setOnClickListener { f() }
                    })
                }
                small("開啟懸浮編輯器") { openEditor(s.id) }
                small("設定") { settingsDialog(s) }
                small("刪除", badColor) {
                    AlertDialog.Builder(act).setTitle("刪除「${s.name}」？")
                        .setPositiveButton("刪除") { _, _ -> Scripts.delete(s.id); act.render() }
                        .setNegativeButton("取消", null).show()
                }
                addView(r)
            }
            button("＋ 新增腳本", primary = true) {
                val s = Script(name = "腳本 ${list.size + 1}")
                Scripts.save(s)
                settingsDialog(s)
            }
        }
        card(root, "使用方式") {
            hint("1. 按「開啟懸浮編輯器」，切到遊戲畫面。\n" +
                "2. 用面板的＋點／＋長按／＋滑／＋等新增步驟，把圓點拖到位置（滑動有起點 n 和終點 n'）。\n" +
                "3. 點圓點可設定等待時間、按住時間，以及「🎨 取色」：只在這個位置是這個顏色時才執行，或一直等到顏色出現。\n" +
                "4. 按 ▶ 執行，圓點會隱藏；按 ■ 停止。💾 儲存，☰ 看全部步驟，拖曳面板標題可移動面板。")
        }
    }

    private fun openEditor(id: String) {
        if (!Perms.overlay(act)) { Perms.openOverlay(act); return }
        ClickerService.open(act, id)
        act.moveTaskToBack(true)
    }

    private fun settingsDialog(s: Script) {
        val u = ui
        val box = u.vbox().apply { setPadding(u.dp(20), u.dp(8), u.dp(20), 0) }
        fun field(label: String, v: String, number: Boolean = true): EditText {
            box.addView(TextView(act).apply { text = label; textSize = 13f; setPadding(0, u.dp(8), 0, 0) })
            val e = EditText(act).apply {
                setText(v); isSingleLine = true
                if (number) inputType = InputType.TYPE_CLASS_NUMBER
            }
            box.addView(e); return e
        }
        val name = field("名稱", s.name, false)
        val loops = field("重複幾輪（0＝無限）", s.loops.toString())
        val maxMin = field("最多執行幾分鐘（0＝不限）", s.maxMin.toString())
        val jpx = field("隨機座標偏移（像素，0＝不偏移）", s.jitterPx.toString())
        val jms = field("隨機時間偏移（毫秒，0＝不偏移）", s.jitterMs.toString())
        AlertDialog.Builder(act).setTitle("腳本設定").setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("儲存") { _, _ ->
                val cur = Scripts.get(s.id) ?: s
                cur.name = name.text.toString().trim().ifEmpty { cur.name }
                cur.loops = loops.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: cur.loops
                cur.maxMin = maxMin.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: cur.maxMin
                cur.jitterPx = jpx.text.toString().toIntOrNull()?.coerceIn(0, 200) ?: cur.jitterPx
                cur.jitterMs = jms.text.toString().toIntOrNull()?.coerceIn(0, 10000) ?: cur.jitterMs
                Scripts.save(cur)
                act.render()
            }
            .setNegativeButton("取消") { _, _ -> act.render() }.show()
    }
}
