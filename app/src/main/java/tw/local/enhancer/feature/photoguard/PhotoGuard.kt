package tw.local.enhancer.feature.photoguard

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import tw.local.enhancer.App
import tw.local.enhancer.core.Apps
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab 2：開相機時切斷 Google 相簿連網，離開相機 N 秒後恢復，
 * 避免邊拍邊上傳造成卡頓發燙。需要 Shizuku。
 *
 * Android 14+：cmd connectivity set-package-networking-enabled（真正的單一 App 防火牆）
 * Android 10–13：限制背景執行＋停止相簿（下次開相簿或排程時會自動繼續備份）
 */
object PhotoGuard {
    const val K_ENABLED = "t2_enabled"
    const val K_DELAY = "t2_delay"
    const val K_NOTIFY = "t2_notify"
    const val K_CAMERAS = "t2_cameras"
    private const val K_BLOCKED = "rt_t2_blocked"      // 執行期：目前是否已阻斷
    private const val K_METHOD = "rt_t2_method"
    private const val K_CHAIN3_WAS = "rt_t2_chain3_was"
    private const val K_LOG = "rt_t2_log"

    const val PHOTOS = "com.google.android.apps.photos"
    private const val NOTIF_ID = 21

    private var cameraSet: Set<String>? = null
    private val restoreRunnable = Runnable { App.bg { restore(App.app, "離開相機") } }

    fun cameras(ctx: Context): Set<String> {
        if (Prefs.has(K_CAMERAS)) return Prefs.set(K_CAMERAS)
        return cameraSet ?: Apps.cameraPackages(ctx).also { cameraSet = it }
    }

    fun isBlocked() = Prefs.bool(K_BLOCKED)

    fun onForeground(ctx: Context, pkg: String) {
        if (!Prefs.bool(K_ENABLED)) return
        val isCam = pkg in cameras(ctx)
        if (isCam) {
            App.main.removeCallbacks(restoreRunnable)
            if (!isBlocked()) App.bg { block(ctx, "開啟相機：" + Apps.label(ctx, pkg)) }
        } else if (isBlocked()) {
            App.main.removeCallbacks(restoreRunnable)
            App.main.postDelayed(restoreRunnable, Prefs.int(K_DELAY, 30) * 1000L)
        }
    }

    @Synchronized
    fun block(ctx: Context, reason: String) {
        if (isBlocked() || !Shell.ready() || !Apps.installed(ctx, PHOTOS)) return
        var method = ""
        if (Build.VERSION.SDK_INT >= 34) {
            val was = Shell.exec("cmd connectivity get-chain3-enabled").out
            Prefs.put(K_CHAIN3_WAS, was.contains("true"))
            val r1 = Shell.exec("cmd connectivity set-chain3-enabled true")
            val r2 = Shell.exec("cmd connectivity set-package-networking-enabled false $PHOTOS")
            if (r1.ok && r2.ok) method = "firewall"
        }
        if (method.isEmpty()) {
            val uid = Apps.uid(ctx, PHOTOS)
            Shell.exec("cmd netpolicy add restrict-background-blacklist $uid")
            Shell.exec("cmd appops set $PHOTOS RUN_ANY_IN_BACKGROUND ignore")
            Shell.exec("am force-stop $PHOTOS")
            method = "stop"
        }
        Prefs.put(K_METHOD, method)
        Prefs.put(K_BLOCKED, true)
        log("阻斷", reason + if (method == "firewall") "（防火牆）" else "（暫停背景＋停止）")
        notify(ctx, "相簿守護中（已暫停 Google 相簿連網）", "原因：$reason\n離開相機後 ${Prefs.int(K_DELAY, 30)} 秒自動恢復")
    }

    @Synchronized
    fun restore(ctx: Context, reason: String) {
        if (!isBlocked()) return
        if (!Shell.ready()) return  // 等 Shizuku 回來再還原（restoreIfStale 會再試）
        when (Prefs.str(K_METHOD)) {
            "firewall" -> {
                Shell.exec("cmd connectivity set-package-networking-enabled true $PHOTOS")
                if (!Prefs.bool(K_CHAIN3_WAS)) Shell.exec("cmd connectivity set-chain3-enabled false")
            }
            else -> {
                val uid = Apps.uid(ctx, PHOTOS)
                Shell.exec("cmd netpolicy remove restrict-background-blacklist $uid")
                Shell.exec("cmd appops set $PHOTOS RUN_ANY_IN_BACKGROUND allow")
            }
        }
        Prefs.put(K_BLOCKED, false)
        log("恢復", reason)
        if (Prefs.bool(K_NOTIFY, true)) {
            notify(ctx, "Google 相簿連網已恢復", reason)
            App.ui(4000) { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        } else {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }
    }

    fun restoreIfStale(ctx: Context) {
        if (isBlocked()) restore(ctx, "App 重新啟動時自動還原")
    }

    private fun notify(ctx: Context, title: String, body: String) {
        if (!Prefs.bool(K_NOTIFY, true)) return
        val n = Notification.Builder(ctx, App.CH_PHOTO)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentText(body)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    @Synchronized
    private fun log(kind: String, msg: String) {
        val now = System.currentTimeMillis()
        val arr = runCatching { JSONArray(Prefs.str(K_LOG, "[]")) }.getOrDefault(JSONArray())
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (now - o.optLong("t") < 24 * 3600_000L) keep.put(o)
        }
        keep.put(JSONObject().put("t", now).put("k", kind).put("m", msg))
        Prefs.put(K_LOG, keep.toString())
    }

    fun logText(): String {
        val arr = runCatching { JSONArray(Prefs.str(K_LOG, "[]")) }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.TAIWAN)
        val lines = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { now - it.optLong("t") < 24 * 3600_000L }
            .reversed()
            .map { fmt.format(Date(it.optLong("t"))) + "  " + it.optString("k") + "｜" + it.optString("m") }
        return if (lines.isEmpty()) "24 小時內尚無紀錄" else lines.take(40).joinToString("\n")
    }
}
