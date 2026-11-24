package tw.local.enhancer.feature.translate

import com.google.mlkit.nl.translate.TranslateLanguage

object Langs {
    const val AUTO = "auto"
    /** 介面用代碼 → 名稱。zh-TW 與 zh-CN 共用 ML Kit 的 "zh" 模型，差在輸出後轉換。 */
    val NAMES = linkedMapOf(
        AUTO to "全球自動",
        "zh-TW" to "繁體中文", "zh-CN" to "簡體中文", "en" to "英文", "ja" to "日文", "ko" to "韓文",
        "th" to "泰文", "vi" to "越南文", "id" to "印尼文", "ms" to "馬來文", "fr" to "法文", "de" to "德文",
        "es" to "西班牙文", "pt" to "葡萄牙文", "it" to "義大利文", "ru" to "俄文", "ar" to "阿拉伯文",
        "hi" to "印地文", "tl" to "菲律賓文", "tr" to "土耳其文", "nl" to "荷蘭文", "pl" to "波蘭文", "uk" to "烏克蘭文"
    )

    fun name(code: String) = NAMES[code] ?: code

    /** 轉成 ML Kit 語言碼 */
    fun mlkit(code: String): String? = when (code) {
        "zh-TW", "zh-CN", "zh" -> TranslateLanguage.CHINESE
        AUTO -> null
        else -> TranslateLanguage.fromLanguageTag(code)
    }

    /** ML Kit 只有一個中文模型，輸出偏簡體；需要繁體時做常用字轉換 */
    fun needsTraditional(target: String) = target == "zh-TW"
}
