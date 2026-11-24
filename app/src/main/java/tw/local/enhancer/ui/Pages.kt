package tw.local.enhancer.ui

import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import tw.local.enhancer.App
import tw.local.enhancer.core.Apps
import tw.local.enhancer.core.Perms
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell
import tw.local.enhancer.feature.callmusic.CallMusicListener
import tw.local.enhancer.feature.photoguard.PhotoGuard
import tw.local.enhancer.feature.strict.StrictMode
import tw.local.enhancer.feature.switcher.FloatingService
import tw.local.enhancer.feature.switcher.RecentTracker
import tw.local.enhancer.feature.tools.CircleSearch
import tw.local.enhancer.feature.tools.ImeManager
import tw.local.enhancer.feature.tools.PowerCamera
import tw.local.enhancer.feature.timeline.TimelineFlow
import tw.local.enhancer.feature.timeline.TimelineShizuku
import tw.local.enhancer.feature.translate.Langs
import tw.local.enhancer.feature.translate.ScrollTranslator
import tw.local.enhancer.feature.voice.VoiceIntercept
import tw.local.enhancer.tiles.FanExpandTile
import tw.local.enhancer.tiles.ScrollTranslateTile

/** 各分頁內容。 */
class Pages(private val act: MainActivity) {
    private val ui get() = act.ui

    enum class Req { A11Y, LISTENER, OVERLAY, USAGE, SHIZUKU, WRITE_SECURE }

    /** 只在有缺權限時顯示「需要授權」卡片 */
    private fun requirements(root: LinearLayout, vararg reqs: Req) {
        val missing = reqs.filter { !ok(it) }
        if (missing.isEmpty()) return
        with(ui) {
            card(root, "需要先授權") {
                for (r in missing) when (r) {
                    Req.A11Y -> status("無障礙服務", false) { Perms.openA11y(act) }
                    Req.LISTENER -> status("通知讀取權限", false) { Perms.openListener(act) }
                    Req.OVERLAY -> status("顯示在其他應用程式上層", false) { Perms.openOverlay(act) }
                    Req.USAGE -> status("使用情況存取", false) { Perms.openUsage(act) }
                    Req.SHIZUKU -> status("Shizuku", false) { requestShizuku() }
                    Req.WRITE_SECURE -> status("寫入安全設定（Shizuku 授權一次）", false) { grantAll() }
                }
                if (Shell.ready()) button("一鍵透過 Shizuku 授予全部權限", primary = true) { grantAll() }
                else hint("提示：開啟 Shizuku 後，可在「⚙ 設定」一鍵授予全部權限。")
            }
        }
    }

    private fun ok(r: Req) = when (r) {
        Req.A11Y -> Perms.a11y(act)
        Req.LISTENER -> Perms.listener(act)
        Req.OVERLAY -> Perms.overlay(act)
        Req.USAGE -> Perms.usage(act)
        Req.SHIZUKU -> Shell.ready()
        Req.WRITE_SECURE -> Perms.writeSecure(act)
    }

    private fun requestShizuku() {
        if (!Shell.running()) {
            act.showText("Shizuku 未啟動", "請先安裝並開啟 Shizuku App，用「無線偵錯」啟動服務後再回來授權。")
            return
        }
        Shell.request { granted -> App.ui { act.toast(if (granted) "Shizuku 已授權" else "未授權"); act.render() } }
    }

    private fun grantAll() {
        if (!Shell.ready()) { requestShizuku(); return }
        App.bg {
            val log = Perms.grantAllViaShizuku(act)
            App.ui { act.showText("授權結果", log); act.render() }
        }
    }

    // =========================== 切換器 ===========================

    fun switcher(root: LinearLayout) = with(ui) {
        requirements(root, Req.OVERLAY, Req.A11Y)
        card(root, "扇形最近任務切換器") {
            hint("用懸浮球、側邊欄手勢、桌面捷徑或下拉快捷磁貼，扇形展開最近開啟的 App。最近清單由無障礙即時追蹤，「使用情況存取」當備援。")
            text(if (FloatingService.running) "狀態：切換器服務執行中" else "狀態：未啟動", bold = true)
            toggle("啟用切換器服務", FloatingService.K_ENABLED) { on ->
                if (on) {
                    if (!Perms.overlay(act)) {
                        Prefs.put(FloatingService.K_ENABLED, false)
                        Perms.openOverlay(act); return@toggle
                    }
                    FloatingService.start(act)
                } else FloatingService.stop(act)
                App.ui(300) { act.render() }
            }
            button("立即展開測試") {
                if (Perms.overlay(act)) FloatingService.start(act, FloatingService.ACTION_EXPAND) else Perms.openOverlay(act)
            }
        }
        val refresh: (Any) -> Unit = { FloatingService.refresh(act) }
        card(root, "懸浮球") {
            toggle("啟用懸浮球", FloatingService.K_BALL, true, "關閉後仍可單獨用側邊欄、桌面捷徑或快捷磁貼。") { refresh(it) }
            toggle("閒置自動縮邊半透明", FloatingService.K_EDGE_HIDE, true) { refresh(it) }
            seek(FloatingService.K_BALL_ALPHA, 20, 100, 85, { "懸浮球透明度：$it%" }) { refresh(it) }
            hint("點一下展開扇形、拖曳移動、長按開啟本 App。")
        }
        card(root, "側邊欄手勢") {
            toggle("啟用側邊欄（從螢幕邊緣往內滑）", FloatingService.K_SIDEBAR) { refresh(it) }
            toggle("側邊欄放右側（關閉為左側）", FloatingService.K_SIDEBAR_RIGHT, true) { refresh(it) }
            seek(FloatingService.K_SIDEBAR_THICK, 6, 40, 14, { "側邊欄厚度：$it dp" }) { refresh(it) }
            seek(FloatingService.K_SIDEBAR_LEN, 60, 400, 160, { "側邊欄長度：$it dp" }) { refresh(it) }
            seek(FloatingService.K_SIDEBAR_ALPHA, 0, 100, 35, { "側邊欄透明度：$it%" }) { refresh(it) }
        }
        card(root, "扇形與清單") {
            seek(FloatingService.K_MAX, 3, 21, 8, { "扇形顯示數量：$it 個" })
            button("管理排除清單（已排除 ${Prefs.set(RecentTracker.K_EXCLUDE).size} 個）") {
                AppPicker.show(act, "不要出現在扇形的 App", Prefs.set(RecentTracker.K_EXCLUDE)) {
                    Prefs.put(RecentTracker.K_EXCLUDE, it); act.render()
                }
            }
            hint("桌面、系統介面、輸入法與本 App 會自動排除。")
        }
        card(root, "其他觸發方式") {
            button("新增桌面捷徑（點一下直接展開扇形）") { pinShortcut() }
            button("加入下拉快捷磁貼：展開扇形") { addTile(ComponentName(act, FanExpandTile::class.java), "展開扇形") }
            hint("也可以在下拉選單編輯模式手動加入「扇形切換器」「展開扇形」「捲動翻譯」「畫圈搜尋」磁貼。")
        }
    }

    private fun iconBitmap(): Bitmap {
        val b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8682A") }
        c.drawCircle(96f, 96f, 96f, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 14f
        c.drawCircle(96f, 96f, 40f, p)
        return b
    }

    private fun pinShortcut() {
        val sm = act.getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) { act.toast("此桌面不支援新增捷徑"); return }
        val intent = Intent(act, TriggerActivity::class.java).setAction(TriggerActivity.ACTION_EXPAND)
        val info = ShortcutInfo.Builder(act, "fan_expand")
            .setShortLabel("扇形切換")
            .setLongLabel("展開扇形最近任務切換器")
            .setIcon(Icon.createWithBitmap(iconBitmap()))
            .setIntent(intent)
            .build()
        sm.requestPinShortcut(info, null)
    }

    private fun addTile(cn: ComponentName, label: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = act.getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(cn, label, Icon.createWithBitmap(iconBitmap()), act.mainExecutor) { }
        } else {
            act.showText("手動加入", "請下拉控制中心 → 編輯 → 找到「$label」拖進去。")
        }
    }

    // =========================== Tab 1 ===========================

    fun callMusic(root: LinearLayout) = with(ui) {
        requirements(root, Req.LISTENER)
        card(root, "來電自動暫停／恢復音樂") {
            hint("偵測 LINE、Telegram、Messenger 等通訊軟體的通話通知（以及 Android 12+ 的系統通話模式），通話時暫停音樂，掛斷後自動恢復播放。")
            toggle("啟用來電自動暫停／恢復", CallMusicListener.K_ENABLED)
            CallMusicListener.instance?.let { text("目前：" + it.stateText(), color = textSub) }
        }
        card(root, "監測的通訊軟體") {
            val comm = CallMusicListener.commApps()
            val installed = comm.filter { Apps.installed(act, it) }
            hint(if (installed.isEmpty()) "尚未勾選已安裝的通訊軟體" else installed.joinToString("、") { Apps.label(act, it) })
            button("選擇監測的通訊軟體（已選 ${installed.size} 個）") {
                AppPicker.show(act, "監測哪些通訊軟體", comm, extra = CallMusicListener.DEFAULT_COMM.toList()) {
                    Prefs.put(CallMusicListener.K_COMM, it); act.render()
                }
            }
            toggle("用系統通話模式輔助偵測", CallMusicListener.K_AUDIO_MODE, true, "Android 12+；任何 VoIP 通話都會觸發。")
        }
        card(root, "控制的音樂播放軟體") {
            val music = Prefs.set(CallMusicListener.K_MUSIC)
            hint(if (music.isEmpty()) "目前：自動控制所有正在播放的媒體" else music.joinToString("、") { Apps.label(act, it) })
            button("選擇音樂播放軟體（未選＝全部）") {
                AppPicker.show(act, "只控制這些播放器", music) { Prefs.put(CallMusicListener.K_MUSIC, it); act.render() }
            }
            toggle("用 Shizuku 補送「播放」媒體鍵", CallMusicListener.K_SHIZUKU, false, "有些播放器不接受恢復指令時使用。")
        }
    }

    // =========================== Tab 2 ===========================

    fun photoGuard(root: LinearLayout) = with(ui) {
        requirements(root, Req.SHIZUKU, Req.A11Y)
        card(root, "Google 相簿算圖守護") {
            hint("開啟相機時暫停 Google 相簿連網，避免邊拍邊上傳造成卡頓發燙；離開相機後依秒數自動恢復。\n" +
                if (Build.VERSION.SDK_INT >= 34) "本機 Android 14+：使用單一 App 防火牆，最乾淨。"
                else "本機 Android 13 以下：改用「限制背景＋暫停相簿」，恢復後相簿會在下次排程時繼續備份。")
            if (!Apps.installed(act, PhotoGuard.PHOTOS)) text("⚠ 沒有安裝 Google 相簿", color = badColor)
            toggle("啟用相簿守護", PhotoGuard.K_ENABLED)
            text(if (PhotoGuard.isBlocked()) "狀態：已暫停相簿連網" else "狀態：相簿連網正常", bold = true)
            button("立即恢復 Google 相簿連網") {
                App.bg { PhotoGuard.restore(act, "手動恢復"); App.ui { act.render() } }
            }
        }
        card(root, "參數") {
            seek(PhotoGuard.K_DELAY, 5, 180, 30, { "離開相機後延遲恢復：$it 秒" })
            toggle("顯示斷網與恢復通知", PhotoGuard.K_NOTIFY, true)
            val cams = PhotoGuard.cameras(act)
            hint("判定為相機的 App：" + cams.joinToString("、") { Apps.label(act, it) })
            button("自訂相機 App") {
                AppPicker.show(act, "哪些 App 算相機", cams) { Prefs.put(PhotoGuard.K_CAMERAS, it); act.render() }
            }
            button("還原預設相機清單") { Prefs.remove(PhotoGuard.K_CAMERAS); act.render() }
        }
        card(root, "近 24 小時紀錄") {
            text(PhotoGuard.logText(), 12.5f, color = textSub)
            button("重新整理") { act.render() }
        }
    }

    // =========================== Tab 3 ===========================

    fun voice(root: LinearLayout) = with(ui) {
        requirements(root, Req.A11Y)
        card(root, "陸版語音助理攔截 → Gemini") {
            hint("長按電源鍵或藍牙耳機叫出藍心小V／小愛／小布等時，自動關掉並改叫出 Gemini。有 Shizuku 會更穩（可強制關閉陸版助理、直接叫出預設助理浮層）。")
            val found = VoiceIntercept.targets(act)
            text("機型：${Build.MANUFACTURER} ${Build.MODEL}\n偵測到的陸版助理：" +
                (if (found.isEmpty()) "無" else found.joinToString("、") { VoiceIntercept.CN_ASSISTANTS[it] ?: Apps.label(act, it) }),
                13f, color = textSub)
            toggle("啟用語音助理攔截", VoiceIntercept.K_ENABLED)
            toggle("攔截時震動回饋", VoiceIntercept.K_VIBRATE, true)
            seek(VoiceIntercept.K_DELAY, 0, 50, 25, { "小V出現後等待：%.1f 秒".format(it / 10f) })
            hint("等待期間若跳出重開機選單（繼續按住電源鍵），就不攔截，可照常重開機。重開機選單約按住 2–3 秒出現；等待越短，Gemini 越快出來，但越可能擋到重開機選單。設 0 秒＝立刻攔截。")
        }
        card(root, "攔截後要叫出") {
            radios(VoiceIntercept.K_TARGET, "overlay", listOf(
                "overlay" to "預設數位助理浮層（設成 Gemini 就是 Gemini 浮層，推薦）",
                "gemini_app" to "開啟 Gemini App",
                "custom" to "開啟指定 App"
            ))
            val custom = Prefs.str(VoiceIntercept.K_CUSTOM)
            button("指定 App：" + (if (custom.isEmpty()) "未選擇" else Apps.label(act, custom))) {
                AppPicker.show(act, "攔截後開啟哪個 App", setOfNotNull(custom.ifEmpty { null }), multi = false) {
                    Prefs.put(VoiceIntercept.K_CUSTOM, it.first()); act.render()
                }
            }
            button("選擇預設數位助理（請設為 Gemini）") { VoiceIntercept.openDefaultAssistantSettings(act) }
            button("手動測試叫出目標", primary = true) { VoiceIntercept.launchTarget(act) }
        }
        card(root, "攔不到？抓取本機助理的套件名") {
            hint("按下後 15 秒內，請用平常的方式（長按電源鍵等）叫出小V，再回到這裡。會列出這段時間出現過的 App，點「加入攔截」即可。")
            button(if (VoiceIntercept.capturing()) "抓取中…（叫出小V後回來）" else "開始抓取（15 秒）", primary = true) {
                if (!Perms.a11y(act)) { Perms.openA11y(act); return@button }
                VoiceIntercept.startCapture(15)
                act.toast("請現在叫出小V")
                act.moveTaskToBack(true)
            }
            val got = VoiceIntercept.capturedList()
            if (got.isNotEmpty()) {
                text("抓到的套件：", bold = true)
                for (p in got) {
                    val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(TextView(act).apply {
                        text = Apps.label(act, p) + "\n" + p
                        setTextColor(textMain); textSize = 13f
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val added = VoiceIntercept.isTarget(p)
                    row.addView(TextView(act).apply {
                        text = if (added) "已攔截" else "加入攔截"
                        setTextColor(if (added) okColor else accent); textSize = 13.5f
                        setPadding(dp(12), dp(8), dp(4), dp(8))
                        if (!added) setOnClickListener {
                            Prefs.put(VoiceIntercept.K_EXTRA, Prefs.set(VoiceIntercept.K_EXTRA) + p)
                            act.toast("已加入：" + Apps.label(act, p)); act.render()
                        }
                    })
                    addView(row)
                }
            }
        }
        card(root, "進階") {
            val extra = Prefs.set(VoiceIntercept.K_EXTRA)
            hint("如果你的手機助理不在內建清單，可以手動加入它的套件。已加入：${extra.size} 個")
            button("加入其他要攔截的助理 App") {
                AppPicker.show(act, "額外攔截的助理", extra, extra = VoiceIntercept.CN_ASSISTANTS.keys.toList()) {
                    Prefs.put(VoiceIntercept.K_EXTRA, it); act.render()
                }
            }
        }
    }

    // =========================== Tab 4 ===========================

    fun strict(root: LinearLayout) = with(ui) {
        requirements(root, Req.WRITE_SECURE, Req.A11Y)
        card(root, "環境隱身（誠實版嚴格模式）") {
            hint("進入你勾選的 App（銀行、支付、超商等）時，自動「真的」關閉開發者選項、USB 偵錯與無線偵錯，離開或鎖屏後恢復原狀。不偽裝任何數值，只是幫你自動做原本要手動做的事。")
            text("⚠ 關閉偵錯會讓 Shizuku 中斷，離開後請重新啟動 Shizuku（Shizuku 13.5+ 可在其設定開啟「開機／連上 Wi-Fi 時自動啟動」）。", 13f, color = badColor)
            toggle("啟用嚴格模式", StrictMode.K_ENABLED)
            text(StrictMode.stateText(act), 13f, color = textSub)
        }
        card(root, "目標 App") {
            val t = Prefs.set(StrictMode.K_TARGETS)
            hint(if (t.isEmpty()) "尚未選擇" else t.joinToString("、") { Apps.label(act, it) })
            button("選擇要套用嚴格模式的 App（已選 ${t.size} 個）") {
                AppPicker.show(act, "進入這些 App 時關閉開發者選項", t) { Prefs.put(StrictMode.K_TARGETS, it); act.render() }
            }
            toggle("同時關閉其他無障礙服務（保留本 App）", StrictMode.K_OFF_A11Y, false, "離開後會恢復原本開著的無障礙服務。")
            toggle("進入時自動重啟該 App（需 Shizuku）", StrictMode.K_RELAUNCH, false, "有些 App 只在啟動時檢查一次，重啟後才會看到已關閉的狀態。")
        }
        card(root, "手動") {
            button("立即還原開發者選項與偵錯") {
                App.bg { StrictMode.restore(act); App.ui { act.render() } }
            }
            hint("例：全家便利商店若第一次仍被偵測，把它滑掉再開一次即可。")
        }
    }

    // =========================== Tab 5 ===========================

    fun translate(root: LinearLayout) = with(ui) {
        requirements(root, Req.A11Y)
        card(root, "穿透式捲動翻譯") {
            hint("開啟後正常滑動頁面，停手約半秒自動辨識文字並把譯文蓋在原位；譯文不攔截觸控，可直接點底下的按鈕。底部膠囊條可拖曳、切換語言、⇄ 對調、點狀態字重翻、✕ 關閉；鎖屏自動關閉。")
            button(if (ScrollTranslator.active) "關閉捲動翻譯" else "啟動捲動翻譯", primary = true) {
                ScrollTranslator.toggle(act)
                act.render()
                if (ScrollTranslator.active) act.moveTaskToBack(true)
            }
            button("來源：" + Langs.name(ScrollTranslator.src())) { langDialog(true) }
            button("目標：" + Langs.name(ScrollTranslator.tgt())) { langDialog(false) }
        }
        card(root, "辨識引擎") {
            radios(ScrollTranslator.K_ENGINE, "hybrid", listOf(
                "hybrid" to "智慧雙引擎（文字節點＋圖片 OCR 補盲，推薦）",
                "node" to "純文字節點（最省電，防截圖頁面也能用）",
                "ocr" to "純畫面 OCR（漫畫、長圖、遊戲）"
            ))
            if (Build.VERSION.SDK_INT < 30) hint("此手機為 Android 10，OCR 需 Android 11 以上，會自動改用文字節點。")
        }
        card(root, "譯文字體大小") {
            radios(ScrollTranslator.K_FONT + "_s", Prefs.int(ScrollTranslator.K_FONT, 120).toString(), listOf(
                "100" to "1.0x（標準）", "120" to "1.2x（推薦）", "135" to "1.35x（大字）", "150" to "1.5x（特大）"
            )) { Prefs.put(ScrollTranslator.K_FONT, it.toInt()) }
        }
        card(root, "離線語言包") {
            toggle("只在 Wi-Fi 下載語言包", ScrollTranslator.K_WIFI)
            hint("每個語言約 30MB，第一次翻譯該語言時會自動下載；先下載好常用的可加快速度。中文（繁／簡）共用同一個語言包。")
            val list = ui.vbox()
            addView(list)
            val loading = text("讀取中…", color = textSub)
            ScrollTranslator.downloadedModels { have ->
                loading.text = "已下載：" + (if (have.isEmpty()) "無" else have.joinToString("、") { mlName(it) })
                val codes = Langs.NAMES.keys.filter { it != Langs.AUTO && it != "zh-CN" }
                for (code in codes) {
                    val ml = Langs.mlkit(code) ?: continue
                    val got = ml in have
                    val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(TextView(act).apply {
                        text = (if (code == "zh-TW") "中文（繁／簡）" else Langs.name(code)) + if (got) "  ✓" else ""
                        setTextColor(if (got) okColor else textMain); textSize = 14f
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(TextView(act).apply {
                        text = if (got) "刪除" else "下載"
                        setTextColor(accent); textSize = 14f
                        setPadding(dp(12), dp(8), dp(4), dp(8))
                        setOnClickListener {
                            text = "處理中…"
                            val cb: (Boolean) -> Unit = { okk -> act.toast(if (okk) "完成" else "失敗"); act.render() }
                            if (got) ScrollTranslator.deleteModel(code, cb) else ScrollTranslator.downloadModel(code, cb)
                        }
                    })
                    list.addView(row)
                }
            }
        }
        card(root, "啟動方式") {
            hint("① 本頁按鈕　② 下拉快捷磁貼「捲動翻譯」　③ Tab 6「畫圈搜尋」旁的連動提示")
            button("加入下拉快捷磁貼：捲動翻譯") { addTile(ComponentName(act, ScrollTranslateTile::class.java), "捲動翻譯") }
        }
    }

    private fun mlName(ml: String): String =
        if (ml == "zh") "中文" else Langs.NAMES.entries.firstOrNull { Langs.mlkit(it.key) == ml }?.value ?: ml

    private fun langDialog(source: Boolean) {
        val codes = Langs.NAMES.keys.filter { source || it != Langs.AUTO }
        AlertDialog.Builder(act).setTitle(if (source) "來源語系" else "目標語言")
            .setItems(codes.map { Langs.name(it) }.toTypedArray()) { _, w ->
                Prefs.put(if (source) ScrollTranslator.K_SRC else ScrollTranslator.K_TGT, codes[w])
                if (ScrollTranslator.active) ScrollTranslator.retranslateNow()
                act.render()
            }.show()
    }

    // =========================== Tab 6 ===========================

    fun tools(root: LinearLayout) = with(ui) {
        card(root, "極速畫圈搜尋（實驗性，需 Shizuku）") {
            hint("透過 Shizuku 直接呼叫 Google 的畫圈搜尋，不用長按導覽列。需要 Google App 是預設助理，且你的 Google App 版本支援畫圈搜尋。")
            button("立即觸發畫圈搜尋", primary = true) { act.moveTaskToBack(true); App.ui(400) { CircleSearch.trigger(act.applicationContext) } }
            toggle("觸發後提示可切換捲動翻譯", CircleSearch.K_LINK_TRANSLATE)
            button("觸發失敗？前往 MiCTS（GitHub）") {
                runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/parallelcc/MiCTS"))) }
            }
        }
        card(root, "雙擊電源鍵開相機") {
            val on = PowerCamera.systemGestureOn(act)
            text("系統手勢目前：" + if (on) "開啟" else "關閉", 13f, color = textSub)
            button(if (on) "關閉系統雙擊電源鍵手勢" else "開啟系統雙擊電源鍵手勢") {
                if (!PowerCamera.setSystemGesture(act, !on)) act.toast("需要 Shizuku 或寫入安全設定權限")
                App.ui(600) { act.render() }
            }
            toggle("備援偵測（系統手勢無效時用）", PowerCamera.K_FALLBACK, false,
                "需無障礙服務。快速按兩下電源鍵（熄屏→亮屏間隔很短）就開相機。")
        }
        timeline(root)
        card(root, "Shizuku 靜默安裝 APK") {
            val log = text("選一個 APK 檔，直接安裝不跳確認畫面。", 13f, color = textSub)
            button("選擇 APK 並安裝") {
                if (!Shell.ready()) { requestShizuku(); return@button }
                act.pickApk { log.text = it }
            }
        }
        card(root, "輸入法管理") {
            hint("清單直接讀系統，不需 Shizuku；設為預設／啟用／停用需要 Shizuku。")
            val box = ui.vbox()
            addView(box)
            button("載入輸入法清單") { ImeManager.list { imes, cur -> fillIme(box, imes, cur) } }
        }
    }

    // ---- 時間軸助手（移植自 Timeline-NoPair 0.26，免 Shizuku） ----

    private fun timeline(root: LinearLayout) = with(ui) {
        val shz = TimelineShizuku.isMode()
        card(root, "Google 地圖時間軸（時間軸助手）") {
            hint("把最小寬度暫時改成 640 dp → 強制停止 Google 地圖與 Google Play 服務 → 還原原本寬度。")
            text("執行方式", bold = true)
            radios(TimelineShizuku.K_MODE, "shizuku", listOf(
                "shizuku" to "Shizuku（推薦）：直接下指令，不用無障礙、不會跳設定頁，幾秒就好",
                "a11y" to "無障礙：不需 Shizuku，自動點設定頁（小米無法用 Shizuku 時用這個）"
            )) { TimelineShizuku.scheduleDaily(act); act.render() }
        }
        if (shz) card(root, "Shizuku 執行") {
            if (!Shell.ready()) status("Shizuku", false) { requestShizuku() }
            val cur = text("讀取目前寬度中…", 13f, color = textSub)
            App.bg { val t = TimelineShizuku.currentText(); App.ui { cur.text = t } }
            hint("原始寬度會自動偵測並在結束時還原，不用手動記下。")
            if (TimelineShizuku.hasPending()) text("⚠ 上次流程未完成，按「僅還原畫面大小」即可恢復", 13f, color = badColor)
            button("開始執行", primary = true) {
                if (!Shell.ready()) { requestShizuku(); return@button }
                act.toast("執行中，約 15 秒…")
                App.bg { val r = TimelineShizuku.run(act); App.ui { act.toast(r); act.render() } }
            }
            button("僅還原畫面大小") {
                App.bg { val r = TimelineShizuku.run(act, restoreOnly = true); App.ui { act.toast(r); act.render() } }
            }
            seek(TimelineShizuku.K_HOLD, 3, 30, 8, { "停止後在 640 dp 停留：$it 秒" })
            hint("讓 Google Play 服務在平板寬度下重新啟動。無效時可以加長。")
        } else card(root, "無障礙執行") {
            if (!Perms.a11y(act)) status("無障礙服務", false) { Perms.openA11y(act) }
            text("首次設定：請先在開發人員選項查看並截圖保存原本的「最小寬度」。第一次先別修改，返回這裡記下原值。", 13.5f)
            button("開啟開發人員選項／查看原值") { openDev() }
            val w = Prefs.int(TimelineFlow.K_WIDTH, -1)
            text(if (w > 0) "你的原始寬度：$w dp" else "尚未保存原始寬度", bold = true)
            button("記下我的原始寬度") { rememberWidth() }
            if (TimelineFlow.hasPending()) {
                text("⚠ 上次流程未完成，待還原寬度 ${TimelineFlow.pendingWidth()} dp", 13f, color = badColor)
            }
            hint("若設定頁沒有自動定位，請手動滑到最小寬度。")
            button("開始自動測試", primary = true) { TimelineFlow.runManual(act, false) }
            button("僅還原畫面大小") { TimelineFlow.runManual(act, true) }
        }
        card(root, "自動執行") {
            val auto = toggle("每次開機後自動執行", TimelineFlow.K_AUTO)
            auto.setOnCheckedChangeListener { b, on ->
                if (on && !Prefs.bool(TimelineFlow.K_TESTED)) {
                    b.isChecked = false
                    act.toast("請先成功執行一次")
                } else Prefs.put(TimelineFlow.K_AUTO, on)
            }
            if (shz) hint("Shizuku 模式：開機後 Shizuku 啟動（可在 Shizuku 設定開機自動啟動）才會執行。")
            val daily = toggle("每日自動執行", TimelineFlow.K_DAILY)
            val timeBtn = button(TimelineFlow.dailyLabel(Prefs.int(TimelineFlow.K_DAILY_MIN, TimelineFlow.DAILY_DEFAULT))) { }
            daily.setOnCheckedChangeListener { b, on ->
                if (on && !shz && Prefs.int(TimelineFlow.K_WIDTH, -1) <= 0) {
                    b.isChecked = false
                    act.toast("請先記下並保存原始寬度")
                } else {
                    TimelineFlow.setDaily(on, Prefs.int(TimelineFlow.K_DAILY_MIN, TimelineFlow.DAILY_DEFAULT))
                    TimelineShizuku.scheduleDaily(act)
                }
            }
            timeBtn.setOnClickListener {
                val m = Prefs.int(TimelineFlow.K_DAILY_MIN, TimelineFlow.DAILY_DEFAULT)
                android.app.TimePickerDialog(act, { _, hh, mm ->
                    val v = hh * 60 + mm
                    TimelineFlow.setDaily(daily.isChecked, v)
                    TimelineShizuku.scheduleDaily(act)
                    timeBtn.text = TimelineFlow.dailyLabel(v)
                }, m / 60, m % 60, true).show()
            }
            hint(if (shz) "依手機當地時間執行，熄屏也會執行。請避開導航時段。"
                else "依手機當地時間執行。鎖定或熄屏時，會等到亮屏並解鎖後再執行。請避開導航時段。")
            text("狀態", bold = true)
            act.timelineStatus = text(Prefs.str(TimelineFlow.K_STATUS, "尚未執行"), 13f, color = textSub)
        }
        card(root, "手動操作") {
            button("1　開啟設定頁，自行調成 640 dp") { openDev() }
            button("2　開啟 Google 地圖資訊") { appDetails("com.google.android.apps.maps") }
            button("3　開啟 Google Play 服務資訊") { appDetails("com.google.android.gms") }
            hint("在上述兩個資訊頁分別點「強制停止」並確認。若系統不允許停止，請還原寬度並結束。")
            button("4　開啟設定，還原我的寬度") { openDev() }
            hint("兩項都停止後，直接把最小寬度改回原值。")
        }
    }

    private fun openDev() {
        runCatching { act.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { act.toast("請從手機設定手動開啟開發人員選項") }
    }

    private fun appDetails(pkg: String) {
        runCatching {
            act.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }.onFailure { act.toast("請從手機設定手動開啟這個頁面") }
    }

    private fun rememberWidth() {
        val et = android.widget.EditText(act).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "填入修改前看到的 dp"
        }
        AlertDialog.Builder(act).setTitle("保存原本的最小寬度").setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val v = et.text.toString().trim().toIntOrNull()
                if (v == null || v < 1 || v > 4096) act.toast("請輸入設定中顯示的數值")
                else { Prefs.put(TimelineFlow.K_WIDTH, v); act.render() }
            }.show()
    }

    private fun fillIme(box: LinearLayout, imes: List<ImeManager.Ime>, cur: String): Unit = with(ui) {
        box.removeAllViews()
        if (imes.isEmpty()) { box.hint("系統沒有回報任何輸入法"); return@with }
        for (ime in imes) {
            box.text((if (ime.id == cur) "★ " else "") + ime.label + (if (!ime.enabled) "（已停用）" else ""), bold = ime.id == cur)
            box.hint(ime.id)
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            fun small(label: String, action: String) {
                row.addView(TextView(act).apply {
                    text = label; setTextColor(accent); textSize = 13.5f
                    setPadding(0, dp(4), dp(18), dp(10))
                    setOnClickListener {
                        if (!Shell.ready()) { requestShizuku(); return@setOnClickListener }
                        ImeManager.run(action, ime.id) { r ->
                            if (r.ok) act.toast("完成") else act.showText("操作失敗", r.toString().ifBlank { "錯誤碼 ${r.code}" })
                            ImeManager.list { a, c -> fillIme(box, a, c) }
                        }
                    }
                })
            }
            if (ime.id != cur) small("設為預設", "set")
            if (ime.enabled && ime.id != cur) small("停用", "disable")
            if (!ime.enabled) small("啟用", "enable")
            box.addView(row)
        }
    }

    // =========================== 設定 ===========================

    fun settings(root: LinearLayout) = with(ui) {
        card(root, "Shizuku 與系統權限中心") {
            text(Shell.statusText(), 13.5f, bold = true)
            if (!Shell.ready()) button("授權 Shizuku", primary = true) { requestShizuku() }
            button("一鍵透過 Shizuku 授予全部權限") { grantAll() }
            space(6)
            status("無障礙服務（前景追蹤、語音攔截、翻譯）", Perms.a11y(act)) { Perms.openA11y(act) }
            status("通知讀取權限（來電音樂）", Perms.listener(act)) { Perms.openListener(act) }
            status("顯示在其他應用程式上層（懸浮球）", Perms.overlay(act)) { Perms.openOverlay(act) }
            status("使用情況存取（最近清單備援）", Perms.usage(act)) { Perms.openUsage(act) }
            status("通知權限（狀態通知）", Perms.notifications(act)) { Perms.openAppNotif(act) }
            status("寫入安全設定（嚴格模式、電源鍵相機）", Perms.writeSecure(act)) { grantAll() }
            hint("Android 13 以上側載的 App，開無障礙前可能要先到「應用程式資訊 → ⋮ → 允許受限制的設定」。")
            button("開啟本 App 的應用程式資訊") { Perms.openAppDetails(act) }
        }
        card(root, "設定備份與還原") {
            hint("匯出全部分頁的開關、目標清單、排除清單與參數成 JSON 檔，可存到本機或 Google 雲端硬碟。")
            button("匯出備份") { act.startExport() }
            button("匯入還原") { act.startImport() }
        }
        card(root, "關於") {
            val v = runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull() ?: "?"
            text("Android補強工具箱 v$v", bold = true)
            hint("全部功能免 root。需要高權限的部分透過 Shizuku 執行；畫面內容只在本機即時處理，不儲存也不上傳。")
        }
    }
}
