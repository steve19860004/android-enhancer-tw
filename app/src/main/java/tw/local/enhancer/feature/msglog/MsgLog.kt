package tw.local.enhancer.feature.msglog

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import tw.local.enhancer.App
import tw.local.enhancer.core.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 救回「收回訊息」：記錄通知內容，對方收回／刪除後你這邊的紀錄還在。
 * 只在本機存一個檔案，不上傳。優先解析 MessagingStyle 逐則訊息。
 */
object MsgLog {
    const val K_ENABLED = "msg_enabled"
    const val K_APPS = "msg_apps"
    private const val MAX = 500

    val DEFAULT_APPS = setOf(
        "jp.naver.line.android", "org.telegram.messenger", "com.facebook.orca",
        "com.instagram.android", "com.whatsapp", "com.facebook.katana", "com.discord"
    )

    fun apps(): Set<String> = if (Prefs.has(K_APPS)) Prefs.set(K_APPS) else DEFAULT_APPS

    private fun file() = File(App.app.filesDir, "msglog.json")

    data class Entry(val time: Long, val pkg: String, val sender: String, val text: String)

    private val seen = HashSet<String>()

    @Synchronized
    private fun load(): JSONArray =
        runCatching { JSONArray(file().readText()) }.getOrDefault(JSONArray())

    @Synchronized
    private fun save(arr: JSONArray) = runCatching { file().writeText(arr.toString()) }

    @Synchronized
    fun onNotification(sbn: StatusBarNotification) {
        if (!Prefs.bool(K_ENABLED)) return
        if (sbn.packageName !in apps()) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val msgs = extractMessages(extras)
        if (msgs.isEmpty()) return
        val arr = load()
        var added = false
        for ((sender, text) in msgs) {
            if (text.isBlank()) continue
            val key = sbn.packageName + "|" + sender + "|" + text
            if (!seen.add(key)) continue
            if (seen.size > 4000) seen.clear()
            arr.put(JSONObject().put("t", System.currentTimeMillis()).put("p", sbn.packageName).put("s", sender).put("m", text))
            added = true
        }
        if (!added) return
        // 超量時裁掉最舊
        while (arr.length() > MAX) arr.remove(0)
        save(arr)
    }

    /** 回傳 (sender, text) 清單 */
    private fun extractMessages(extras: Bundle): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        // 1) MessagingStyle：逐則訊息最準
        val history = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (history != null) {
            for (p in history) {
                val b = p as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString() ?: continue
                val sender = b.getCharSequence("sender")?.toString() ?: ""
                out.add(sender to text)
            }
            if (out.isNotEmpty()) return out
        }
        // 2) 一般通知：標題＝對方，內文＝訊息
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isNotBlank() && !text.startsWith("收回") && !text.contains("則新訊息")) out.add(title to text)
        return out
    }

    fun entries(filterPkg: String? = null, query: String = ""): List<Entry> {
        val arr = load()
        val list = ArrayList<Entry>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val e = Entry(o.optLong("t"), o.optString("p"), o.optString("s"), o.optString("m"))
            if (filterPkg != null && e.pkg != filterPkg) continue
            if (query.isNotEmpty() && !e.text.contains(query, true) && !e.sender.contains(query, true)) continue
            list.add(e)
        }
        return list
    }

    fun clear() = runCatching { file().delete() }.let { seen.clear() }

    fun fmt(t: Long): String = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(Date(t))
}
