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
