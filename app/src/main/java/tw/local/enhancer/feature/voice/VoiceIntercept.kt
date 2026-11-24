package tw.local.enhancer.feature.voice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import tw.local.enhancer.App
import tw.local.enhancer.core.Apps
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell

/**
 * Tab 3：長按電源鍵／藍牙耳機叫出陸版語音助理時，關掉它並改叫出 Gemini（或指定 App）。
 */
object VoiceIntercept {
    const val K_ENABLED = "t3_enabled"
    const val K_TARGET = "t3_target"          // overlay | gemini_app | custom
    const val K_CUSTOM = "t3_custom_pkg"
    const val K_VIBRATE = "t3_vibrate"
    const val K_EXTRA = "t3_extra_pkgs"

    /** 已知的陸版語音助理套件 */
    val CN_ASSISTANTS = linkedMapOf(
        "com.vivo.agent" to "vivo Jovi／藍心小V",
        "com.vivo.aiagent" to "vivo 藍心小V（新版）",
        "com.vivo.ai.copilot" to "vivo 藍心小V（OriginOS 5+）",
        "com.vivo.assistant" to "vivo 智慧助理",
        "com.vivo.voicewakeup" to "vivo 語音喚醒",
        "com.miui.voiceassist" to "小米 小愛同學",
        "com.xiaomi.aiasst.service" to "小米 AI 助理",
        "com.heytap.speechassist" to "OPPO／一加 小布助理",
        "com.coloros.speechassist" to "ColorOS 小布助理",
        "com.huawei.vassistant" to "華為 小藝",
        "com.hihonor.magicvoice" to "榮耀 YOYO",
        "com.samsung.android.bixby.agent" to "三星 Bixby"
    )

    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    const val GEMINI_APP = "com.google.android.apps.bard"

    private var lastFire = 0L

    // ---- 抓取模式：記下這段時間出現過的所有視窗套件，用來找出本機助理的真正套件名 ----
    private const val K_CAPTURED = "rt_t3_captured"
    @Volatile private var captureUntil = 0L
    private val captured = LinkedHashSet<String>()

    fun startCapture(seconds: Int = 15) {
        synchronized(captured) { captured.clear() }
        Prefs.remove(K_CAPTURED)
        captureUntil = System.currentTimeMillis() + seconds * 1000L
    }

    fun capturing() = System.currentTimeMillis() < captureUntil

    fun capturedList(): List<String> = Prefs.str(K_CAPTURED).split(',').filter { it.isNotBlank() }

    /** 服務收到任何事件時呼叫 */
    fun onAnyPackage(ctx: Context, pkg: String) {
        if (!capturing()) return
        if (pkg == ctx.packageName || pkg == "com.android.systemui" || pkg == "android") return
        synchronized(captured) {
            if (captured.add(pkg)) Prefs.put(K_CAPTURED, captured.joinToString(","))
        }
    }

    fun isTarget(pkg: String) = pkg in CN_ASSISTANTS.keys || pkg in Prefs.set(K_EXTRA)

    fun enabled() = Prefs.bool(K_ENABLED)

    fun targets(ctx: Context): List<String> =
        (CN_ASSISTANTS.keys + Prefs.set(K_EXTRA)).filter { Apps.installed(ctx, it) }

    fun onWindow(svc: AccessibilityService, pkg: String, cls: String?) {
        if (!Prefs.bool(K_ENABLED)) return
        if (!isTarget(pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastFire < 2500) return
        lastFire = now
        intercept(svc, pkg)
    }

    private fun intercept(svc: AccessibilityService, pkg: String) {
        // 1. 關掉陸版助理
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (Shell.ready()) App.bg { Shell.exec("am force-stop ${Shell.q(pkg)}") }
        if (Prefs.bool(K_VIBRATE, true)) vibrate(svc)
        // 2. 稍等浮層消失，再叫出目標
        App.ui(280) { launchTarget(svc) }
    }

    fun launchTarget(ctx: Context) {
        when (Prefs.str(K_TARGET, "overlay")) {
            "gemini_app" -> if (!Apps.launch(ctx, GEMINI_APP)) assistOverlay(ctx)
            "custom" -> {
                val p = Prefs.str(K_CUSTOM)
                if (p.isEmpty() || !Apps.launch(ctx, p)) assistOverlay(ctx)
            }
            else -> assistOverlay(ctx)
        }
    }

    /** 叫出「預設數位助理」浮層（設成 Gemini 就是 Gemini 浮層） */
    fun assistOverlay(ctx: Context) {
        if (Shell.ready()) {
            App.bg {
                val r = Shell.exec("cmd voiceinteraction show")
                if (!r.ok || r.toString().contains("Unknown", true) || r.toString().contains("error", true)) {
                    App.ui { assistIntent(ctx) }
                }
            }
        } else {
            assistIntent(ctx)
        }
    }

    private fun assistIntent(ctx: Context) {
        val tries = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND).setPackage(GOOGLE_APP),
            Intent(Intent.ACTION_ASSIST),
            Intent(Intent.ACTION_VOICE_COMMAND)
        )
        for (i in tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i); true }.getOrDefault(false)) return
        }
        Toast.makeText(ctx, "無法叫出助理，請確認已安裝 Google／Gemini 並設為預設助理", Toast.LENGTH_LONG).show()
    }

    fun openDefaultAssistantSettings(ctx: Context) {
        val tries = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i); true }.getOrDefault(false)) return
        }
    }

    private fun vibrate(ctx: Context) {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }
}
