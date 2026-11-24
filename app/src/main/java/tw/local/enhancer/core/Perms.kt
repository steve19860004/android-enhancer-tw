package tw.local.enhancer.core

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import tw.local.enhancer.feature.callmusic.CallMusicListener

object Perms {
    fun a11yComponent(ctx: Context) = ComponentName(ctx, EnhancerA11yService::class.java)
    fun listenerComponent(ctx: Context) = ComponentName(ctx, CallMusicListener::class.java)

    fun a11y(ctx: Context): Boolean {
        if (EnhancerA11yService.instance != null) return true
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val c = a11yComponent(ctx)
        return s.split(':').any { ComponentName.unflattenFromString(it) == c }
    }

    fun listener(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val c = listenerComponent(ctx)
        return s.split(':').any { ComponentName.unflattenFromString(it) == c }
    }

    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun usage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun notifications(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun writeSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    // ---- 開系統設定頁 ----
    fun openA11y(ctx: Context) = start(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    fun openListener(ctx: Context) = start(ctx, Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    fun openOverlay(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
    fun openUsage(ctx: Context) = start(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    fun openAppNotif(ctx: Context) = start(
        ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    )
    fun openAppDetails(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))

    private fun start(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** 透過 Shizuku 一次授予全部權限（背景執行緒呼叫），回傳執行紀錄 */
    fun grantAllViaShizuku(ctx: Context): String {
        val pkg = ctx.packageName
        val log = StringBuilder()
        fun run(label: String, cmd: String) {
            val r = Shell.exec(cmd)
            log.append(if (r.ok) "✓ " else "✗ ").append(label)
            if (!r.ok && r.toString().isNotBlank()) log.append("：").append(r.toString().take(120))
            log.append('\n')
        }
        // Android 13+ 側載 App 需先解除「受限制的設定」才可開無障礙／通知讀取
        run("解除受限制設定", "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        run("寫入安全設定 WRITE_SECURE_SETTINGS", "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        run("使用情況存取", "appops set $pkg GET_USAGE_STATS allow")
        run("懸浮窗", "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= 33) run("通知權限", "pm grant $pkg android.permission.POST_NOTIFICATIONS")
        run("通知讀取權限", "cmd notification allow_listener ${listenerComponent(ctx).flattenToString()}")
        run("電池最佳化白名單", "dumpsys deviceidle whitelist +$pkg")
        // 無障礙：附加到既有清單，不覆蓋使用者其他服務
        val comp = a11yComponent(ctx).flattenToString()
        val cur = Shell.exec("settings get secure enabled_accessibility_services").out.trim()
        val list = cur.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        if (list.none { ComponentName.unflattenFromString(it) == a11yComponent(ctx) }) list.add(comp)
        run("無障礙服務", "settings put secure enabled_accessibility_services ${Shell.q(list.joinToString(":"))}")
        run("無障礙總開關", "settings put secure accessibility_enabled 1")
        return log.toString().trim()
    }
}
