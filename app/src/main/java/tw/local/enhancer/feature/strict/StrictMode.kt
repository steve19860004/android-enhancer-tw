package tw.local.enhancer.feature.strict

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import tw.local.enhancer.App
import tw.local.enhancer.core.Apps
import tw.local.enhancer.core.Perms
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell

/**
 * Tab 4（誠實版）：進入指定 App 時「真的」關閉開發者選項與 USB／無線偵錯，
 * 可選擇一併關閉其他無障礙服務；離開或鎖屏後恢復原狀。
 * 不偽裝任何數值，只是自動化你原本要手動做的事。
 *
 * 需要 WRITE_SECURE_SETTINGS（用 Shizuku 授權一次即可，之後不需 Shizuku）。
 */
object StrictMode {
    const val K_ENABLED = "t4_enabled"
    const val K_TARGETS = "t4_targets"
    const val K_OFF_A11Y = "t4_off_other_a11y"
    const val K_RELAUNCH = "t4_relaunch"

    private const val K_ACTIVE = "rt_t4_active"
    private const val K_SAVED_ADB = "rt_t4_adb"
    private const val K_SAVED_WIFI = "rt_t4_adbwifi"
    private const val K_SAVED_DEV = "rt_t4_dev"
    private const val K_SAVED_A11Y = "rt_t4_a11y"
    private const val NOTIF_ID = 31

    private const val ADB_WIFI = "adb_wifi_enabled"

    @Volatile private var suppressUntil = 0L

    fun isActive() = Prefs.bool(K_ACTIVE)

    fun onForeground(ctx: Context, prev: String, pkg: String) {
        if (!Prefs.bool(K_ENABLED)) return
        val targets = Prefs.set(K_TARGETS)
        if (pkg in targets) {
            if (!isActive()) activate(ctx, pkg)
        } else if (isActive() && System.currentTimeMillis() > suppressUntil) {
            App.bg { restore(ctx) }
        }
    }

    fun onScreenOff(ctx: Context) {
        if (isActive()) App.bg { restore(ctx) }
    }

    private fun activate(ctx: Context, pkg: String) {
        if (!Perms.writeSecure(ctx)) return
        suppressUntil = System.currentTimeMillis() + 4000
        val relaunch = Prefs.bool(K_RELAUNCH) && Shell.ready()
        App.bg {
            // 要先用 Shizuku 停掉 App，關掉 ADB 之後 Shizuku 就會斷線
            if (relaunch) Shell.exec("am force-stop ${Shell.q(pkg)}")
            applyOff(ctx)
            if (relaunch) App.ui(350) { Apps.launch(ctx, pkg) }
        }
    }

    @Synchronized
    private fun applyOff(ctx: Context) {
        if (isActive()) return
        val cr = ctx.contentResolver
        Prefs.put(K_SAVED_ADB, Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0))
        Prefs.put(K_SAVED_WIFI, Settings.Global.getInt(cr, ADB_WIFI, 0))
        Prefs.put(K_SAVED_DEV, Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0))
        val a11y = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        Prefs.put(K_SAVED_A11Y, a11y)
        Prefs.put(K_ACTIVE, true)
        runCatching {
            Settings.Global.putInt(cr, ADB_WIFI, 0)
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0)
            Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
            if (Prefs.bool(K_OFF_A11Y)) {
                // 只保留本 App 的無障礙（離開時才偵測得到）
                val mine = Perms.a11yComponent(ctx)
                val keep = a11y.split(':').filter { ComponentName.unflattenFromString(it) == mine }
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, keep.joinToString(":"))
            }
        }
        notifyActive(ctx)
    }

    @Synchronized
    fun restore(ctx: Context) {
        if (!isActive()) return
        if (!Perms.writeSecure(ctx)) return
        val cr = ctx.contentResolver
        runCatching {
            Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Prefs.int(K_SAVED_DEV, 0))
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, Prefs.int(K_SAVED_ADB, 0))
            Settings.Global.putInt(cr, ADB_WIFI, Prefs.int(K_SAVED_WIFI, 0))
            if (Prefs.bool(K_OFF_A11Y)) {
                val saved = Prefs.str(K_SAVED_A11Y)
                if (saved.isNotEmpty()) Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, saved)
            }
        }
        Prefs.put(K_ACTIVE, false)
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    fun restoreIfStale(ctx: Context) {
        val fg = tw.local.enhancer.core.EnhancerA11yService.instance?.foregroundPkg
        if (isActive() && fg !in Prefs.set(K_TARGETS)) restore(ctx)
    }

    private fun notifyActive(ctx: Context) {
        val n = Notification.Builder(ctx, App.CH_STRICT)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("嚴格模式：已暫時關閉開發者選項與偵錯")
            .setContentText("離開該 App 或鎖屏後自動恢復")
            .setOngoing(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    fun stateText(ctx: Context): String {
        val cr = ctx.contentResolver
        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
        val wifi = Settings.Global.getInt(cr, ADB_WIFI, 0)
        return "目前：開發者選項 ${onOff(dev)}｜USB 偵錯 ${onOff(adb)}｜無線偵錯 ${onOff(wifi)}" +
            if (isActive()) "\n（嚴格模式作用中）" else ""
    }

    private fun onOff(v: Int) = if (v != 0) "開" else "關"
}
