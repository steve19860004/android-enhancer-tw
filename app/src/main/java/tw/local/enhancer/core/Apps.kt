package tw.local.enhancer.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap

object Apps {
    data class Info(val pkg: String, val label: String, val system: Boolean)

    private val labelCache = ConcurrentHashMap<String, String>()
    private val activityCache = ConcurrentHashMap<String, Boolean>()

    /** 有桌面圖示的 App */
    fun launchable(ctx: Context, includeSystem: Boolean = true): List<Info> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map { Info(it.packageName, it.loadLabel(pm).toString(), (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
            .filter { includeSystem || !it.system }
            .sortedBy { it.label.lowercase() }
    }

    fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    fun label(ctx: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).loadLabel(ctx.packageManager).toString() }
            .getOrDefault(pkg)
    }

    fun icon(ctx: Context, pkg: String): Drawable? =
        runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    fun uid(ctx: Context, pkg: String): Int =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)

    fun isSystem(ctx: Context, pkg: String) = runCatching {
        (ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Toast.makeText(ctx, "找不到可啟動的畫面：$pkg", Toast.LENGTH_SHORT).show(); return false
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }

    fun hasLaunch(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    /** 這個 window 事件是否真的是 Activity（排除對話框、浮層、通知欄） */
    fun isActivity(ctx: Context, pkg: String, cls: String?): Boolean {
        if (cls.isNullOrEmpty()) return false
        val key = "$pkg/$cls"
        return activityCache.getOrPut(key) {
            runCatching { ctx.packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true }.getOrDefault(false)
        }
    }

    fun launchers(ctx: Context): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
    }

    fun imePackages(ctx: Context): Set<String> =
        ctx.getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()

    /** 相機 App：系統解析 + 常見陸版／國際版相機套件 */
    fun cameraPackages(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val out = mutableSetOf<String>()
        for (a in listOf(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.INTENT_ACTION_VIDEO_CAMERA)) {
            pm.queryIntentActivities(Intent(a), 0).forEach { out.add(it.activityInfo.packageName) }
        }
        listOf(
            "com.android.camera", "com.android.camera2", "com.oplus.camera", "com.oppo.camera",
            "com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.huawei.camera",
            "com.hihonor.camera", "com.motorola.camera3", "com.asus.camera", "com.sonyericsson.android.camera"
        ).filter { installed(ctx, it) }.forEach { out.add(it) }
        return out
    }
}
