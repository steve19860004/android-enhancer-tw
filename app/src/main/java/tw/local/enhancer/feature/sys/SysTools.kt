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

    fun onShizukuReady() = App.bg { reapplyFirewall() }
}
