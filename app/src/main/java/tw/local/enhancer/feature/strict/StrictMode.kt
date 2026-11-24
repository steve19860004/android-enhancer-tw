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

    // 手動一鍵隱身（和自動嚴格模式共用設定，但各自記狀態）
    const val K_M_OFF_ADB = "t4_m_off_adb"      // 手動隱身時是否也關 ADB（預設否）
    private const val K_M_ACTIVE = "rt_t4_m_active"
    private const val K_M_A11Y = "rt_t4_m_a11y"
    private const val K_M_ADB = "rt_t4_m_adb"
    private const val K_M_WIFI = "rt_t4_m_wifi"
    private const val K_M_DEV = "rt_t4_m_dev"

    @Volatile private var suppressUntil = 0L

    fun isActive() = Prefs.bool(K_ACTIVE)
    fun manualActive() = Prefs.bool(K_M_ACTIVE)

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

    /**
     * 手動一鍵隱身：真的把所有無障礙服務關掉並記下來（可選一併關 ADB），
     * 進銀行／超商 App 前按一次，出來再「恢復」。
     * 用 WRITE_SECURE_SETTINGS 寫入，就算關了 ADB、Shizuku 斷線，之後仍能自己還原。
     */
    @Synchronized
    fun manualHide(ctx: Context): Boolean {
        if (!Perms.writeSecure(ctx) || manualActive()) return false
        val cr = ctx.contentResolver
        val a11y = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        Prefs.put(K_M_A11Y, a11y)
        val offAdb = Prefs.bool(K_M_OFF_ADB)
        if (offAdb) {
            Prefs.put(K_M_ADB, Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0))
            Prefs.put(K_M_WIFI, Settings.Global.getInt(cr, ADB_WIFI, 0))
            Prefs.put(K_M_DEV, Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0))
        }
        Prefs.put(K_M_ACTIVE, true)
        return runCatching {
            // 真的停用全部無障礙（含本 App 自己的），不是偽裝
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (offAdb) {
                Settings.Global.putInt(cr, ADB_WIFI, 0)
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0)
                Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
            }
        }.isSuccess
    }

    @Synchronized
    fun manualRestore(ctx: Context): Boolean {
        if (!manualActive()) return false
        if (!Perms.writeSecure(ctx)) return false
        val cr = ctx.contentResolver
        runCatching {
            val saved = Prefs.str(K_M_A11Y)
            if (saved.isNotEmpty()) Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, saved)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (saved.isEmpty()) 0 else 1)
            if (Prefs.has(K_M_ADB)) {
                Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Prefs.int(K_M_DEV, 1))
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, Prefs.int(K_M_ADB, 1))
                Settings.Global.putInt(cr, ADB_WIFI, Prefs.int(K_M_WIFI, 0))
            }
        }
        Prefs.put(K_M_ACTIVE, false)
        listOf(K_M_ADB, K_M_WIFI, K_M_DEV).forEach { Prefs.remove(it) }
        return true
    }

    fun manualStateText(ctx: Context): String {
        if (!manualActive()) return "目前：環境正常（未隱身）"
        val offAdb = Prefs.has(K_M_ADB)
        return "🟢 已隱身：無障礙服務已暫停" + if (offAdb) "，ADB／開發者選項已關閉" else "，ADB 維持開啟"
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
