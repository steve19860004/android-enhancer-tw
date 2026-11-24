package tw.local.enhancer.feature.translate

import android.icu.text.Transliterator
import tw.local.enhancer.App

/**
 * 簡體 → 台灣繁體（含台灣用語，如 软件→軟體、设置→設定）。
 * ML Kit 中文模型只輸出簡體，所以翻成繁中時要轉換。
 * 字表取自 OpenCC（Apache 2.0）：先做簡→繁（詞組優先、最長匹配），再套台灣用語。
 */
object ZhConvert {
    private class Dict(val map: HashMap<String, String>, val maxLen: Int)

    private var s2t: Dict? = null
    private var tw: Dict? = null

    private fun load(name: String): Dict {
        val m = HashMap<String, String>(16000)
        var max = 1
        runCatching {
            App.app.assets.open(name).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val k = line.substring(0, tab)
                    m[k] = line.substring(tab + 1)
                    if (k.length > max) max = k.length
                }
            }
        }
        return Dict(m, max)
    }

    @Synchronized private fun s2tDict() = s2t ?: load("s2tw.txt").also { s2t = it }
    @Synchronized private fun twDict() = tw ?: load("twphrases.txt").also { tw = it }

    private fun replace(s: String, d: Dict): String {
        if (d.map.isEmpty()) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            var len = minOf(d.maxLen, s.length - i)
            var hit = false
            while (len >= 1) {
                val v = d.map[s.substring(i, i + len)]
                if (v != null) { sb.append(v); i += len; hit = true; break }
                len--
            }
            if (!hit) { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    fun s2tw(s: String): String {
        val d = s2tDict()
        val trad = if (d.map.isEmpty()) icu(s, "Hans-Hant") else replace(s, d)
        return replace(trad, twDict())
    }

    fun t2s(s: String): String = icu(s, "Hant-Hans")

    private fun icu(s: String, id: String): String =
        runCatching { Transliterator.getInstance(id).transliterate(s) }.getOrDefault(s)
}
