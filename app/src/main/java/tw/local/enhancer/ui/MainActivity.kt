package tw.local.enhancer.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import tw.local.enhancer.App
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell
import tw.local.enhancer.feature.switcher.FloatingService
import tw.local.enhancer.feature.tools.SilentInstall
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val REQ_EXPORT = 101
        const val REQ_IMPORT = 102
        const val REQ_APK = 103
        private const val K_TAB = "ui_tab"
    }

    val tabs = listOf(
        "sw" to "切換器", "t1" to "1 來電音樂", "t2" to "2 相簿守護", "t3" to "3 語音攔截",
        "t4" to "4 環境隱身", "t5" to "5 捲動翻譯", "t6" to "6 工具", "set" to "⚙ 設定"
    )

    lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var scroll: ScrollView
    private lateinit var pages: Pages

    var installLog: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()  // 用自己畫的標題，不要系統標題列（避免重複）
        ui = Ui(this)
        pages = Pages(this)
        window.statusBarColor = ui.pageBg
        window.navigationBarColor = ui.pageBg

        val root = ui.vbox().apply { setBackgroundColor(ui.pageBg); fitsSystemWindows = true }
        val header = ui.vbox().apply { setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(4)) }
        header.addView(TextView(this).apply {
            text = "Android補強工具箱"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ui.textMain)
        })
        subtitle = TextView(this).apply { textSize = 12.5f; setTextColor(ui.textSub) }
        header.addView(subtitle)
        root.addView(header)

        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        hs.addView(tabBar)
        root.addView(hs)

        scroll = ScrollView(this)
        content = ui.vbox().apply { setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(40)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Prefs.bool(FloatingService.K_ENABLED) && !FloatingService.running) {
            runCatching { FloatingService.start(this) }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    fun currentTab() = Prefs.str(K_TAB, "sw")

    fun selectTab(id: String) {
        Prefs.put(K_TAB, id)
        scroll.scrollTo(0, 0)
        render()
    }

    /** 重畫目前分頁（權限、狀態可能改變） */
    fun render() {
        subtitle.text = Shell.statusText()
        tabBar.removeAllViews()
        val cur = currentTab()
        for ((id, label) in tabs) {
            val sel = id == cur
            val t = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (sel) android.graphics.Color.WHITE else ui.textMain)
                background = ui.round(if (sel) ui.accent else ui.cardBg, 18)
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                setOnClickListener { selectTab(id) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = ui.dp(8)
            tabBar.addView(t, lp)
        }
        content.removeAllViews()
        when (cur) {
            "sw" -> pages.switcher(content)
            "t1" -> pages.callMusic(content)
            "t2" -> pages.photoGuard(content)
            "t3" -> pages.voice(content)
            "t4" -> pages.strict(content)
            "t5" -> pages.translate(content)
            "t6" -> pages.tools(content)
            else -> pages.settings(content)
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    fun showText(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("確定", null).show()
    }

    // ---- 備份還原／安裝 APK ----

    fun startExport() {
        val name = "enhancer-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.TAIWAN).format(Date()) + ".json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, REQ_EXPORT)
    }

    fun startImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        startActivityForResult(i, REQ_IMPORT)
    }

    fun pickApk(log: (String) -> Unit) {
        installLog = log
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/vnd.android.package-archive")
        startActivityForResult(i, REQ_APK)
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> App.bg {
                val json = Prefs.exportJson(null)
                val n = json.optJSONObject("data")?.length() ?: 0
                val ok = runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toString(2).toByteArray()) }
                }.isSuccess
                App.ui { toast(if (ok) "已匯出 $n 項設定" else "匯出失敗") }
            }
            REQ_IMPORT -> App.bg {
                val n = runCatching {
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    val obj = JSONObject(text)
                    require(obj.optString("app") == "tw.local.enhancer") { "不是本 App 的備份檔" }
                    Prefs.importJson(obj)
                }
                App.ui {
                    n.onSuccess {
                        toast("已還原 $it 項設定")
                        FloatingService.refresh(this)
                        render()
                    }.onFailure { toast("還原失敗：" + (it.message ?: "")) }
                }
            }
            REQ_APK -> {
                val log = installLog ?: return
                var size = -1L
                runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) size = c.getLong(0)
                    }
                }
                val input = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
                if (input == null) { log("無法讀取檔案"); return }
                log("安裝中…（${size / 1024} KB）")
                SilentInstall.install(input, size) { r ->
                    log(if (r.out.contains("Success")) "✓ 安裝成功" else "✗ 安裝失敗：$r")
                }
            }
        }
    }
}
