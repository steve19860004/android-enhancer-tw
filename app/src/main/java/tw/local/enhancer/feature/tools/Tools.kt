package tw.local.enhancer.feature.tools

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import tw.local.enhancer.App
import tw.local.enhancer.core.Perms
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell
import tw.local.enhancer.feature.switcher.FloatingService
import java.io.InputStream
import kotlin.math.min

/** 畫圈搜尋（實驗性）：透過 Shizuku 呼叫 VoiceInteractionManagerService.showSessionFromSession */
object CircleSearch {
    const val K_LINK_TRANSLATE = "t6_cts_link_translate"

    fun trigger(ctx: Context) {
        if (!Shell.ready()) {
            Toast.makeText(ctx, "畫圈搜尋需要 Shizuku", Toast.LENGTH_SHORT).show(); return
        }
        App.bg {
            val ok = runCatching { invoke() }.getOrDefault(false)
            App.ui {
                if (!ok) {
                    Toast.makeText(ctx, "觸發失敗：請確認 Google App 為預設助理且支援畫圈搜尋", Toast.LENGTH_LONG).show()
                } else if (Prefs.bool(K_LINK_TRANSLATE)) {
                    // 連動：畫圈搜尋時順便提示可開捲動翻譯
                    Toast.makeText(ctx, "長按快捷磁貼「捲動翻譯」可直接切換穿透翻譯", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun invoke(): Boolean {
        val raw: IBinder = SystemServiceHelper.getSystemService("voiceinteraction") ?: return false
        val binder = ShizukuBinderWrapper(raw)
        val stub = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return false
        val args = Bundle().apply {
            putLong("invocation_time_ms", SystemClock.elapsedRealtime())
            putInt("omni.entry_point", 1)
            putBoolean("micts_trigger", true)
        }
        val m = service.javaClass.methods.firstOrNull { it.name == "showSessionFromSession" } ?: return false
        val params = m.parameterTypes
        val callArgs: Array<Any?> = when (params.size) {
            3 -> arrayOf(null, args, 7)
            4 -> arrayOf(null, args, 7, "hyperOS_home")
            else -> return false
        }
        val r = m.invoke(service, *callArgs)
        return r as? Boolean ?: true
    }
}

/** 雙擊電源鍵開相機：先開系統內建手勢，備援用螢幕開關事件偵測 */
object PowerCamera {
    const val K_FALLBACK = "t6_power_cam_fallback"
    private const val SYS_KEY = "camera_double_tap_power_gesture_disabled"
    private var lastOff = 0L

    fun systemGestureOn(ctx: Context): Boolean =
        Settings.Secure.getInt(ctx.contentResolver, SYS_KEY, 0) == 0

    fun setSystemGesture(ctx: Context, on: Boolean): Boolean {
        if (Perms.writeSecure(ctx)) {
            return runCatching { Settings.Secure.putInt(ctx.contentResolver, SYS_KEY, if (on) 0 else 1) }.getOrDefault(false)
        }
        if (Shell.ready()) {
            App.bg { Shell.exec("settings put secure $SYS_KEY ${if (on) 0 else 1}") }
            return true
        }
        return false
    }

    /** 亮著時按一下＝熄屏，緊接再按一下＝亮屏；間隔很短就視為雙擊 */
    fun onScreen(ctx: Context, on: Boolean) {
        if (!Prefs.bool(K_FALLBACK)) return
        val now = SystemClock.elapsedRealtime()
        if (!on) { lastOff = now; return }
        if (now - lastOff in 1..550) {
            lastOff = 0
            val km = ctx.getSystemService(KeyguardManager::class.java)
            val action = if (km.isKeyguardLocked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
            else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
            runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

/** Google 地圖時間軸：把最小寬度調成 640dp 再開時間軸（平板版面才有完整時間軸） */
object TimelineDpi {
    fun apply(ctx: Context, cb: (String) -> Unit) {
        val s = FloatingService.screenSize(ctx)
        val shortSide = min(s.x, s.y)
        val density = shortSide * 160 / 640
        App.bg {
            val r = Shell.exec("wm density $density")
            App.ui {
                cb(if (r.ok) "已設定 density=$density（約 640dp）" else "失敗：$r")
                if (r.ok) App.ui(900) { openTimeline(ctx) }
            }
        }
    }

    fun reset(cb: (String) -> Unit) {
        App.bg {
            val r = Shell.exec("wm density reset")
            App.ui { cb(if (r.ok) "已還原原本的顯示大小" else "失敗：$r") }
        }
    }

    fun openTimeline(ctx: Context) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/timeline"))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            Toast.makeText(ctx, "找不到 Google 地圖", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Shizuku 靜默安裝 APK：直接把檔案串流給 pm install */
object SilentInstall {
    fun install(input: InputStream, size: Long, cb: (Shell.Result) -> Unit) {
        App.bg {
            val r = if (size > 0) Shell.execWithStdin("pm install -r -S $size", input)
            else Shell.Result(-1, "", "無法取得檔案大小")
            App.ui { cb(r) }
        }
    }
}

/** 輸入法管理 */
object ImeManager {
    data class Ime(val id: String, val enabled: Boolean)

    fun list(cb: (List<Ime>, String) -> Unit) {
        App.bg {
            val all = Shell.exec("ime list -a -s").out.lines().map { it.trim() }.filter { it.contains('/') }
            val enabled = Shell.exec("ime list -s").out.lines().map { it.trim() }.toSet()
            val cur = Settings.Secure.getString(App.app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            App.ui { cb(all.map { Ime(it, it in enabled) }, cur) }
        }
    }

    fun run(cmd: String, cb: (Shell.Result) -> Unit) {
        App.bg { val r = Shell.exec(cmd); App.ui { cb(r) } }
    }
}
