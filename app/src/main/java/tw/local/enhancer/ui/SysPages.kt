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
import tw.local.enhancer.feature.volume.VolumeBooster
import tw.local.enhancer.feature.mock.MockLocation
import tw.local.enhancer.feature.msglog.MsgLog

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
        card(root, "通知保活中心（修 FCM／通知延遲）") {
            hint("陸版系統會殺掉自行安裝的 Google FCM 與國際 App，造成 LINE／FB／WhatsApp 通知延遲、或要開 App 才一次跳出。一鍵把它們加進電池白名單、開背景執行、強制開通知權限。重開機會自動重套。需 Shizuku。")
            button("一鍵保活建議清單（Google＋通訊 App）", primary = true) {
                if (!Shell.ready()) { pages.requestShizuku(); return@button }
                App.bg {
                    val present = SysTools.KEEPALIVE_SUGGEST.filter { Apps.installed(act, it) }
                    for (p in present) SysTools.enableKeepalive(p, true)
                    App.ui { act.toast("已保活 ${present.size} 個 App"); act.render() }
                }
            }
            button("自選要保活的 App") {
                if (!Shell.ready()) { pages.requestShizuku(); return@button }
                AppPicker.show(act, "要保活哪些 App", SysTools.keepaliveSet(), extra = SysTools.KEEPALIVE_SUGGEST) { picked ->
                    App.bg {
                        for (p in picked) SysTools.enableKeepalive(p, true)
                        for (p in SysTools.keepaliveSet()) if (p !in picked) SysTools.enableKeepalive(p, false)
                        App.ui { act.toast("已更新保活清單"); act.render() }
                    }
                }
            }
            val onKa = SysTools.keepaliveSet().filter { Apps.installed(act, it) }
            hint("目前保活：" + (if (onKa.isEmpty()) "（尚未設定）" else onKa.joinToString("、") { Apps.label(act, it) }))
            button("強制重連 FCM（重啟 Google Play 服務）") {
                if (!Shell.ready()) { pages.requestShizuku(); return@button }
                App.bg { SysTools.reconnectFcm(); App.ui { act.toast("已重啟 Google Play 服務，稍等通知重連") } }
            }
            hint("vivo「自啟動／關聯啟動」是專屬設定，Shizuku 設不到，請另到「設定→應用與權限→權限管理→自啟動」手動開；FCM 被殺是系統層級，保活能大幅改善但非 100%。")
        }
        card(root, "APK 匯出備份（免 Shizuku）") {
            hint("把已安裝的 App 匯出成 APK 檔，方便換機、備份或分享。有分割檔的 App 會匯出成 .apks（zip）。不需要 Shizuku。")
            button("選擇要匯出的 App", primary = true) {
                AppPicker.show(act, "選擇要匯出 APK 的 App", emptySet(), multi = false, includeSystem = true) { sel ->
                    sel.firstOrNull()?.let { act.exportApk(it) }
                }
            }
        }
        card(root, "強制全 App 分割畫面／小窗") {
            hint("開啟後，原本被廠商限制不給分割畫面或自由視窗的 App 也能用。需寫入安全設定或 Shizuku；部分設定重開機後才完全生效。")
            val on = SysTools.freeformOn(act)
            text(if (on) "狀態：已開啟" else "狀態：關閉", bold = true)
            button(if (on) "關閉" else "開啟強制分割畫面／小窗", primary = !on) {
                if (!SysTools.setFreeform(act, !on)) act.toast("需要寫入安全設定或 Shizuku") else act.toast("已" + if (!on) "開啟（建議重開機）" else "關閉")
                act.render()
            }
        }
        card(root, "全域強制深色模式") {
            hint("把不支援深色的 App 也套上深色（開發者選項的 Override force-dark）。需 Shizuku。部分 App 會自己擋掉；force-dark 重開機會失效，本 App 會在開機後自動重套。")
            if (!Shell.ready()) status("Shizuku", false) { pages.requestShizuku() }
            val on = SysTools.darkOn()
            text(if (on) "狀態：已開啟" else "狀態：關閉", bold = true)
            button(if (on) "關閉全域深色" else "開啟全域深色", primary = !on) {
                if (!Shell.ready()) { pages.requestShizuku(); return@button }
                if (!SysTools.setDark(!on)) act.toast("失敗") else act.toast("已" + (if (!on) "開啟（部分 App 需重啟才變色）" else "關閉"))
                act.render()
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

    // =========================== Tab 9 音量 ===========================

    fun volume(root: LinearLayout) = with(ui) {
        card(root, "音量增大器（免 root）") {
            hint("用系統效果把整機媒體音量推到 100% 以上，最高 +15 dB（約 5.6 倍）。")
            text("⚠ 推太大會破音，長時間大音量也會傷聽力與喇叭，請適量。", 12.5f, color = badColor)
            val on = toggle("啟用音量增大", VolumeBooster.K_ENABLED) { v ->
                if (v) { if (!VolumeBooster.enable()) act.showText("無法啟用", "這台手機可能擋了全域音訊效果。\n" + VolumeBooster.lastError) }
                else VolumeBooster.disable()
                act.render()
            }
            if (VolumeBooster.enabled() && VolumeBooster.lastError.isNotEmpty())
                text("⚠ " + VolumeBooster.lastError, 12.5f, color = badColor)
            val gTv = text("目前增益：+%.1f dB".format(VolumeBooster.gainDb()), bold = true)
            val sb = android.widget.SeekBar(act).apply {
                max = VolumeBooster.MAX_MB
                progress = VolumeBooster.gainMb()
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar, p: Int, u: Boolean) { gTv.text = "目前增益：+%.1f dB".format(p / 100f) }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar) {
                        if (!VolumeBooster.enabled()) { on.isChecked = true; VolumeBooster.enable() }
                        VolumeBooster.setGain(s.progress)
                    }
                })
            }
            addView(sb)
            hint("拖曳手動調整增益；關閉時歸零恢復原音量。")
        }
        card(root, "音量鍵自動增大（需無障礙）") {
            hint("開啟後：媒體音量已經最大時，再按「音量＋」就會自動往上加增益（一直按持續增加）；按「音量－」先把增益降回來，降到 0 再恢復正常調音量。")
            if (!Perms.a11y(act)) status("無障礙服務", false) { Perms.openA11y(act) }
            if (!Perms.overlay(act)) hint("提示：開「顯示在其他應用程式上層」可在調整時顯示增益浮層，否則用 Toast 提示。")
            toggle("啟用音量鍵攔截", VolumeBooster.K_KEYHOOK) { v ->
                if (v && !Perms.a11y(act)) { Perms.openA11y(act) }
                Prefs.put(VolumeBooster.K_KEYHOOK, v)
            }
        }
        card(root, "快速測試") {
            button("增益 +1 dB") { VolumeBooster.bump(act, VolumeBooster.STEP_MB); act.render() }
            button("增益 -1 dB") { VolumeBooster.bump(act, -VolumeBooster.STEP_MB); act.render() }
            button("歸零") { VolumeBooster.setGain(0); act.render() }
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

    // =========================== Tab 10 救回收回訊息 ===========================

    fun msgLog(root: LinearLayout) = with(ui) {
        if (!Perms.listener(act)) card(root, "需要通知讀取權限") {
            hint("靠通知記錄保存訊息，對方收回／刪除後你這邊還看得到。請開啟通知讀取權限。")
            status("通知讀取權限", false) { Perms.openListener(act) }
        }
        card(root, "通知歷史（救回已清掉的通知）") {
            hint("開啟後系統會保留最近清掉／滑掉的通知，誤清也找得回來。陸版預設常是關的。需 Shizuku 或寫入安全設定。")
            val on = SysTools.notifHistoryOn(act)
            text("目前：" + (if (on) "已開啟" else "未開啟"), 13f, color = if (on) textSub else badColor)
            button(if (on) "關閉通知歷史" else "開啟通知歷史", primary = !on) {
                if (!Shell.ready() && !Perms.writeSecure(act)) { pages.requestShizuku(); return@button }
                val ok = SysTools.setNotifHistory(act, !on)
                act.toast(if (ok) (if (!on) "已開啟" else "已關閉") else "失敗")
                act.render()
            }
            button("開啟通知歷史畫面") { SysTools.openNotifHistory(act) }
        }
        card(root, "救回收回訊息") {
            hint("會記錄下方勾選 App 的通知訊息，存在本機、不上傳。對方一收回，你這邊的紀錄還在。")
            text("⚠ 僅供個人備查，請勿用於騷擾或散布他人訊息。", 12.5f, color = badColor)
            toggle("啟用訊息記錄", MsgLog.K_ENABLED)
            val apps = MsgLog.apps().filter { Apps.installed(act, it) }
            hint("記錄的 App：" + (if (apps.isEmpty()) "（未安裝勾選的 App）" else apps.joinToString("、") { Apps.label(act, it) }))
            button("選擇要記錄的 App") {
                AppPicker.show(act, "記錄哪些 App 的訊息", MsgLog.apps(), extra = MsgLog.DEFAULT_APPS.toList()) {
                    Prefs.put(MsgLog.K_APPS, it); act.render()
                }
            }
        }
        card(root, "訊息紀錄") {
            val search = android.widget.EditText(act).apply { hint = "搜尋訊息或對方名稱…"; isSingleLine = true }
            addView(search)
            val box = ui.vbox()
            addView(box)
            fun refresh() {
                box.removeAllViews()
                val list = MsgLog.entries(query = search.text.toString().trim()).take(100)
                if (list.isEmpty()) { box.hint("還沒有紀錄。開啟記錄後，收到的訊息會出現在這裡。"); return }
                for (e in list) {
                    box.text(MsgLog.fmt(e.time) + "　" + (if (e.sender.isNotBlank()) e.sender + "（" + Apps.label(act, e.pkg) + "）" else Apps.label(act, e.pkg)), 12f, color = textSub)
                    box.text(e.text, 14.5f)
                    box.space(6)
                }
            }
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = refresh()
            })
            button("重新整理") { refresh() }
            button("清除全部紀錄") {
                AlertDialog.Builder(act).setTitle("清除全部訊息紀錄？")
                    .setPositiveButton("清除") { _, _ -> MsgLog.clear(); refresh() }.setNegativeButton("取消", null).show()
            }
            refresh()
        }
    }

    // =========================== Tab 11 假 GPS 定位 ===========================

    fun mockGps(root: LinearLayout) = with(ui) {
        card(root, "假 GPS 定位（需 Shizuku）") {
            hint("用 Shizuku 把本 App 設成「模擬定位來源」，再持續送出你指定的座標。")
            text("⚠ 違反多數 App／遊戲的服務條款，可能被封號或鎖定，請自行評估風險。", 12.5f, color = badColor)
            if (!Shell.ready()) { status("Shizuku", false) { pages.requestShizuku() }; return@card }
            val stat = text("檢查授權中…", 13f, bold = true)
            App.bg { val g = MockLocation.isMockGranted(act); App.ui { stat.text = if (g) "✓ 已設為模擬定位來源" else "✗ 尚未設為模擬定位來源" } }
            button("用 Shizuku 設為模擬定位來源", primary = true) {
                App.bg {
                    val (ok, log) = MockLocation.grantMockViaShizuku(act)
                    App.ui {
                        if (ok) { act.toast("已設定完成"); act.render() }
                        else act.showText("設定結果（請截圖回報）", log + "\n\n若仍未成功，可改用下方『開發者選項手動選取』。")
                    }
                }
            }
            button("改用開發者選項手動選取") {
                runCatching { act.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                act.showText("手動選取步驟", "在開發人員選項裡找到「選取模擬位置資訊應用程式 / Select mock location app」，選「免Root百寶箱」。有些機型搜尋「模擬位置」就找得到。")
            }
        }
        card(root, "座標") {
            val latE = android.widget.EditText(act).apply { setText(MockLocation.lat().toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED; hint = "緯度 lat" }
            val lngE = android.widget.EditText(act).apply { setText(MockLocation.lng().toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED; hint = "經度 lng" }
            text("緯度 Latitude", 13f, color = textSub); addView(latE)
            text("經度 Longitude", 13f, color = textSub); addView(lngE)
            fun saveCoord() { Prefs.put(MockLocation.K_LAT, latE.text.toString().trim()); Prefs.put(MockLocation.K_LNG, lngE.text.toString().trim()) }
            hint("常用地點：")
            for ((name, la, ln) in listOf(Triple("台北101", "25.0339", "121.5645"), Triple("台北車站", "25.0478", "121.5170"), Triple("高雄85大樓", "22.6126", "120.3000"))) {
                button(name) { latE.setText(la); lngE.setText(ln); saveCoord() }
            }
            text(if (MockLocation.running) "狀態：定位模擬中" else "狀態：未啟動", bold = true)
            button(if (MockLocation.running) "停止假定位" else "開始假定位", primary = !MockLocation.running) {
                saveCoord()
                if (MockLocation.running) MockLocation.stop(act)
                else {
                    if (!MockLocation.isMockGranted(act)) { act.toast("請先用 Shizuku 設為模擬定位來源"); return@button }
                    MockLocation.start(act)
                }
                App.ui(900) {
                    if (MockLocation.running && MockLocation.lastError.isNotEmpty())
                        act.showText("假定位啟動失敗", MockLocation.lastError + "\n\n多半是還沒設為模擬定位來源，或這台 ROM 擋了測試定位。先完成上面的授權再試。")
                    act.render()
                }
            }
            hint("啟動後保持本服務在背景；部分 App 需重新整理定位或重開才會抓到。")
        }
    }

}
