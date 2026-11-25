package tw.local.enhancer.feature.voicefix

import android.content.Context
import android.provider.Settings
import tw.local.enhancer.core.Apps
import tw.local.enhancer.core.Perms
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell

/**
 * Google 地圖麥克風修正（實驗）：
 * 陸版 ROM 升級後常把系統預設「語音辨識服務」改成陸版的（藍心／訊飛等），
 * Google 地圖的語音搜尋走的是系統預設語音辨識，所以失靈；把它切回 Google 即可。
 * 全程可還原。需 Shizuku 或寫入安全設定。
 */
object VoiceFix {
    const val GOOGLE_PKG = "com.google.android.googlequicksearchbox"
    const val GOOGLE_RECOG = "com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
    const val MAPS_PKG = "com.google.android.apps.maps"

    private const val RECOG = "voice_recognition_service"
    private const val ASSIST = "assistant"
    private const val VIS = "voice_interaction_service"
    private const val K_SAVED = "rt_vf_saved_recog"

    fun googleInstalled(ctx: Context) = Apps.installed(ctx, GOOGLE_PKG)

    fun read(ctx: Context): String {
        val cr = ctx.contentResolver
        fun g(k: String) = Settings.Secure.getString(cr, k)?.ifBlank { "(空)" } ?: "(未設定)"
        return "語音辨識服務 voice_recognition_service：\n${g(RECOG)}\n\n" +
            "預設助理 assistant：\n${g(ASSIST)}\n\n" +
            "語音互動 voice_interaction_service：\n${g(VIS)}"
    }

    fun isGoogle(ctx: Context): Boolean =
        (Settings.Secure.getString(ctx.contentResolver, RECOG) ?: "").contains(GOOGLE_PKG)

    fun applyGoogle(ctx: Context): Boolean {
        if (!googleInstalled(ctx)) return false
        val cur = Settings.Secure.getString(ctx.contentResolver, RECOG) ?: ""
        if (!Prefs.has(K_SAVED)) Prefs.put(K_SAVED, cur)   // 記下原值（只記第一次）
        return put(ctx, RECOG, GOOGLE_RECOG)
    }

    fun restore(ctx: Context): Boolean {
        if (!Prefs.has(K_SAVED)) return false
        val ok = put(ctx, RECOG, Prefs.str(K_SAVED))
        if (ok) Prefs.remove(K_SAVED)
        return ok
    }

    fun canRestore() = Prefs.has(K_SAVED)

    // ---- Google 地圖麥克風（appops 層）----
    // 症狀：權限介面顯示「允許」，但講話時麥克風圖示完全不跳、最後「無法辨識」。
    // 原因：vivo 陸版常把 Google 地圖的 RECORD_AUDIO appop 設成 ignore，
    //       系統不報錯、改回傳靜音給辨識引擎，所以收不到音。
    // 修正：用 Shizuku 把 appop 強制設回 allow，並補 pm grant。全程只動 Google 地圖。
    private fun run(cmd: String) = if (Shell.ready()) Shell.exec(cmd) else null

    fun mapsMicState(): String {
        if (!Shell.ready()) return "需要先啟動 Shizuku 才能查 appops。"
        val op = run("cmd appops get $MAPS_PKG RECORD_AUDIO")
        val all = run("cmd appops get $MAPS_PKG")
        val perm = run("dumpsys package $MAPS_PKG | grep -i record_audio")
        val sb = StringBuilder()
        sb.append("RECORD_AUDIO appop：\n")
        sb.append((op?.out ?: op?.err ?: "").ifBlank { "(無輸出)" }).append("\n\n")
        sb.append("套件權限（manifest 層）：\n")
        sb.append((perm?.out ?: "").ifBlank { "(查不到)" }).append("\n\n")
        sb.append("其他非預設 appops（找有沒有 ignore/deny）：\n")
        sb.append((all?.out ?: "").ifBlank { "(全部預設)" })
        return sb.toString()
    }

    fun fixMapsMic(): String {
        if (!Shell.ready()) return "需要先啟動 Shizuku。"
        val sb = StringBuilder()
        val r1 = run("cmd appops set $MAPS_PKG RECORD_AUDIO allow")
        sb.append(if (r1?.ok == true) "✓ appops RECORD_AUDIO 設為 allow\n" else "✗ appops 設定失敗：${r1?.err}\n")
        // 補 runtime 權限（已授予會直接過，失敗忽略）
        run("pm grant $MAPS_PKG android.permission.RECORD_AUDIO")
        // 部分機型有獨立的沙箱錄音 op，一起放行（舊系統沒有會報錯，忽略）
        run("cmd appops set $MAPS_PKG RECORD_AUDIO_SANDBOXED allow")
        sb.append("\n改完的狀態：\n")
        val after = run("cmd appops get $MAPS_PKG RECORD_AUDIO")
        sb.append((after?.out ?: "").ifBlank { "(無輸出)" })
        sb.append("\n\n→ 回 Google 地圖按麥克風講一句，看圖示會不會跳動。")
        return sb.toString()
    }

    private fun put(ctx: Context, key: String, value: String): Boolean {
        // 這類 secure 設定用 Shizuku（adb shell）最保險，寫入安全設定當備援
        if (Shell.ready()) {
            return Shell.exec("settings put secure $key ${Shell.q(value)}").ok
        }
        if (Perms.writeSecure(ctx)) {
            return runCatching { Settings.Secure.putString(ctx.contentResolver, key, value) }.isSuccess
        }
        return false
    }
}
