package tw.local.enhancer.feature.sys

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import tw.local.enhancer.App
import tw.local.enhancer.core.Apps
import tw.local.enhancer.core.Perms
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 系統工具（Shizuku／寫入安全設定，免 root）。耗時的函式都要在背景執行緒呼叫。 */
object SysTools {

    data class Pkg(val pkg: String, val label: String, val system: Boolean, val enabled: Boolean, val installed: Boolean)

    /** 全部套件（含已對使用者移除的） */
    fun allPackages(ctx: Context, includeSystem: Boolean): List<Pkg> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val list = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
        return list.asSequence()
            .filter { it.packageName != ctx.packageName }
            .map {
                Pkg(
                    it.packageName, it.loadLabel(pm).toString(),
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    it.enabled,
                    (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0
                )
            }
            .filter { includeSystem || !it.system }
            .sortedWith(compareBy<Pkg> { it.enabled && it.installed }.thenBy { it.label.lowercase() })
            .toList()
    }

    // ---------------- 凍結／移除 ----------------
    private const val K_REMOVED = "sys_removed"   // 由本 App 移除過的套件，方便還原

    fun removedByMe(): Set<String> = Prefs.set(K_REMOVED)

    fun disable(pkg: String) = Shell.exec("pm disable-user --user 0 ${Shell.q(pkg)}")
    fun enable(pkg: String) = Shell.exec("pm enable ${Shell.q(pkg)}")

    /** 只對目前使用者移除（保留系統分割區的原檔，可隨時還原） */
    fun uninstallForUser(pkg: String): Shell.Result {
        val r = Shell.exec("pm uninstall -k --user 0 ${Shell.q(pkg)}")
        if (r.out.contains("Success")) Prefs.put(K_REMOVED, removedByMe() + pkg)
        return r
    }

    fun restore(pkg: String): Shell.Result {
        val r = Shell.exec("cmd package install-existing ${Shell.q(pkg)}")
        if (r.ok || r.out.contains("installed", true)) Prefs.put(K_REMOVED, removedByMe() - pkg)
        return r
    }

    // ---------------- 單一 App 斷網（Android 14+） ----------------
    const val K_BLOCKED = "sys_net_blocked"

    fun firewallSupported() = Build.VERSION.SDK_INT >= 34

    fun blocked(): Set<String> = Prefs.set(K_BLOCKED)

    fun setBlocked(pkg: String, block: Boolean): Shell.Result {
        Shell.exec("cmd connectivity set-chain3-enabled true")
        val r = Shell.exec("cmd connectivity set-package-networking-enabled ${!block} ${Shell.q(pkg)}")
        if (r.ok) Prefs.put(K_BLOCKED, if (block) blocked() + pkg else blocked() - pkg)
        return r
    }

    /** 防火牆規則重開機會消失，Shizuku 連上時重新套用 */
    fun reapplyFirewall() {
        if (!firewallSupported() || blocked().isEmpty() || !Shell.ready()) return
        Shell.exec("cmd connectivity set-chain3-enabled true")
        for (p in blocked()) Shell.exec("cmd connectivity set-package-networking-enabled false ${Shell.q(p)}")
    }

    // ---------------- 背景保活／限制 ----------------
    fun whitelist(): Set<String> {
        val out = Shell.exec("dumpsys deviceidle whitelist").out
        // 格式：user,com.example,10123
        return out.lines().mapNotNull { l -> l.split(',').getOrNull(1)?.trim() }.toSet()
    }

    fun setWhitelist(pkg: String, on: Boolean) =
        Shell.exec("dumpsys deviceidle whitelist ${if (on) "+" else "-"}${Shell.q(pkg)}")

    fun bgRestricted(pkg: String): Boolean =
        Shell.exec("cmd appops get ${Shell.q(pkg)} RUN_ANY_IN_BACKGROUND").out.contains("ignore")

    fun setBgRestricted(pkg: String, restricted: Boolean): Shell.Result {
        val mode = if (restricted) "ignore" else "allow"
        val r = Shell.exec("cmd appops set ${Shell.q(pkg)} RUN_ANY_IN_BACKGROUND $mode")
        Shell.exec("cmd appops set ${Shell.q(pkg)} RUN_IN_BACKGROUND $mode")
        if (!restricted) Shell.exec("am set-standby-bucket ${Shell.q(pkg)} active")
        return r
    }

    // ---------------- 通知保活（修 FCM／通知延遲）----------------
    const val K_KEEPALIVE = "sys_keepalive"   // 使用者選的保活 App

    // 常被陸版系統殺掉而導致通知延遲的 App：Google 全家桶(FCM) + 國際通訊
    val KEEPALIVE_SUGGEST = listOf(
        "com.google.android.gms",                 // Google Play 服務（FCM 核心）
        "com.google.android.gsf",                 // Google 服務框架
        "com.android.vending",                    // Play 商店
        "com.google.android.googlequicksearchbox",// Google App
        "jp.naver.line.android",                  // LINE
        "com.facebook.katana", "com.facebook.orca",
        "com.whatsapp", "org.telegram.messenger",
        "com.instagram.android",
        "com.google.android.gm",                  // Gmail
        "com.shopee.tw",                          // 蝦皮
        "org.thoughtcrime.securesms",             // Signal
        "com.microsoft.office.outlook"
    )

    fun keepaliveSet(): Set<String> = Prefs.set(K_KEEPALIVE)

    /** 對單一 App 套用全套保活：電池白名單＋背景＋通知權限＋standby active */
    fun applyKeepalive(pkg: String): Boolean {
        val a = Shell.exec("dumpsys deviceidle whitelist +${Shell.q(pkg)}")
        Shell.exec("cmd appops set ${Shell.q(pkg)} RUN_ANY_IN_BACKGROUND allow")
        Shell.exec("cmd appops set ${Shell.q(pkg)} RUN_IN_BACKGROUND allow")
        Shell.exec("cmd appops set ${Shell.q(pkg)} POST_NOTIFICATION allow")   // 解「允許通知」置灰
        Shell.exec("pm grant ${Shell.q(pkg)} android.permission.POST_NOTIFICATIONS")
        Shell.exec("am set-standby-bucket ${Shell.q(pkg)} active")
        return a.ok
    }

    fun enableKeepalive(pkg: String, on: Boolean) {
        if (on) { applyKeepalive(pkg); Prefs.put(K_KEEPALIVE, keepaliveSet() + pkg) }
        else {
            Shell.exec("dumpsys deviceidle whitelist -${Shell.q(pkg)}")
            Prefs.put(K_KEEPALIVE, keepaliveSet() - pkg)
        }
    }

    /** 強制重連 FCM：重啟 Google Play 服務，逼它重新建立推送長連線 */
    fun reconnectFcm() = Shell.exec("am force-stop com.google.android.gms")

    /** 重開機後電池白名單會被清，Shizuku 連上時重新套用 */
    fun reapplyKeepalive() {
        if (!Shell.ready()) return
        for (p in keepaliveSet()) applyKeepalive(p)
    }

    // ---------------- 動畫速度 ----------------
    private val ANIM_KEYS = listOf(
        Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE
    )

    fun animScale(ctx: Context): Float = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    fun setAnimScale(ctx: Context, v: Float): Boolean {
        if (Perms.writeSecure(ctx)) {
            return runCatching { ANIM_KEYS.forEach { Settings.Global.putFloat(ctx.contentResolver, it, v) }; true }.getOrDefault(false)
        }
        if (!Shell.ready()) return false
        ANIM_KEYS.forEach { Shell.exec("settings put global $it $v") }
        return true
    }

    // ---------------- 私人 DNS ----------------
    val DNS_PRESETS = linkedMapOf(
        "off" to "關閉",
        "opportunistic" to "自動（系統預設）",
        "dns.adguard-dns.com" to "AdGuard（擋廣告）",
        "family.adguard-dns.com" to "AdGuard 家庭（擋廣告＋成人內容）",
        "one.one.one.one" to "Cloudflare（快、不擋廣告）",
        "dns.google" to "Google"
    )

    fun dnsText(ctx: Context): String {
        val mode = Settings.Global.getString(ctx.contentResolver, "private_dns_mode") ?: "opportunistic"
        val host = Settings.Global.getString(ctx.contentResolver, "private_dns_specifier") ?: ""
        return when (mode) {
            "off" -> "關閉"
            "hostname" -> host
            else -> "自動"
        }
    }

    /** value：off / opportunistic / 主機名稱 */
    fun setDns(ctx: Context, value: String): Boolean {
        val pairs = when (value) {
            "off", "opportunistic" -> listOf("private_dns_mode" to value)
            else -> listOf("private_dns_specifier" to value, "private_dns_mode" to "hostname")
        }
        if (Perms.writeSecure(ctx)) {
            return runCatching { pairs.forEach { (k, v) -> Settings.Global.putString(ctx.contentResolver, k, v) }; true }.getOrDefault(false)
        }
        if (!Shell.ready()) return false
        pairs.forEach { (k, v) -> Shell.exec("settings put global $k ${Shell.q(v)}") }
        return true
    }

    // ---------------- APK 匯出 ----------------
    /** 有分割檔的 App 匯出成 .apks（zip），否則 .apk */
    fun exportName(ctx: Context, pkg: String): String {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        val ver = runCatching { ctx.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: ""
        val base = (Apps.label(ctx, pkg) + "_" + ver).replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        return base + if (ai.splitSourceDirs.isNullOrEmpty()) ".apk" else ".apks"
    }

    fun exportTo(ctx: Context, pkg: String, dest: Uri): String {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        val splits = ai.splitSourceDirs ?: emptyArray()
        ctx.contentResolver.openOutputStream(dest, "wt")!!.use { os ->
            if (splits.isEmpty()) {
                File(ai.sourceDir).inputStream().use { it.copyTo(os) }
            } else {
                ZipOutputStream(os).use { zip ->
                    for (path in listOf(ai.sourceDir) + splits) {
                        zip.putNextEntry(ZipEntry(File(path).name))
                        File(path).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
        return if (splits.isEmpty()) "已匯出 APK" else "已匯出 .apks（含 ${splits.size} 個分割檔，可用 SAI 等工具安裝）"
    }

    // ---------------- 強制分割畫面／自由小窗 ----------------
    private const val FREEFORM = "enable_freeform_support"
    private const val RESIZABLE = "force_resizable_activities"

    fun freeformOn(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, FREEFORM, 0) == 1 &&
            Settings.Global.getInt(ctx.contentResolver, RESIZABLE, 0) == 1

    /** 回傳是否成功；需寫入安全設定或 Shizuku。重開機後生效最完整。 */
    fun setFreeform(ctx: Context, on: Boolean): Boolean {
        val v = if (on) 1 else 0
        if (Perms.writeSecure(ctx)) {
            return runCatching {
                Settings.Global.putInt(ctx.contentResolver, FREEFORM, v)
                Settings.Global.putInt(ctx.contentResolver, RESIZABLE, v)
            }.isSuccess
        }
        if (!Shell.ready()) return false
        Shell.exec("settings put global $FREEFORM $v")
        Shell.exec("settings put global $RESIZABLE $v")
        return true
    }

    // ---------------- 全域強制深色 ----------------
    const val K_DARK = "sys_force_dark"

    fun darkOn() = Prefs.bool(K_DARK)

    /** 系統深色＋對不支援的 App 強制深色（force-dark）。需 Shizuku。 */
    fun setDark(on: Boolean): Boolean {
        if (!Shell.ready()) return false
        val r1 = Shell.exec("cmd uimode night ${if (on) "yes" else "no"}")
        // force-dark：開發者選項「Override force-dark」底層的旗標
        Shell.exec("setprop debug.hwui.force_dark ${if (on) "true" else "false"}")
        Prefs.put(K_DARK, on)
        return r1.ok
    }

    /** 開機後重新套用（force-dark 的 setprop 重開機會消失） */
    fun reapplyDark() {
        if (!darkOn() || !Shell.ready()) return
        Shell.exec("cmd uimode night yes")
        Shell.exec("setprop debug.hwui.force_dark true")
    }

    // ---------------- 通知歷史（救回已清掉的通知） ----------------
    fun notifHistoryOn(ctx: Context): Boolean =
        Settings.Secure.getInt(ctx.contentResolver, "notification_history_enabled", 0) == 1

    fun setNotifHistory(ctx: Context, on: Boolean): Boolean {
        val v = if (on) 1 else 0
        if (Shell.ready()) return Shell.exec("settings put secure notification_history_enabled $v").ok
        if (Perms.writeSecure(ctx)) return runCatching {
            Settings.Secure.putInt(ctx.contentResolver, "notification_history_enabled", v)
        }.isSuccess
        return false
    }

    fun openNotifHistory(ctx: Context) {
        val go = android.content.Intent("android.settings.NOTIFICATION_HISTORY")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(go) }.onFailure {
            runCatching {
                ctx.startActivity(android.content.Intent(Settings.ACTION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun onShizukuReady() = App.bg { reapplyFirewall(); reapplyDark(); reapplyKeepalive() }
}
