package tw.local.enhancer.feature.clicker

import org.json.JSONArray
import org.json.JSONObject
import tw.local.enhancer.core.Prefs
import java.util.UUID

/**
 * 自動點擊腳本。座標一律是螢幕座標（px），以編輯時的螢幕方向為準。
 */
class Step(
    var type: String = TAP,     // tap | long | swipe | wait
    var x: Int = 0, var y: Int = 0,
    var x2: Int = 0, var y2: Int = 0,
    var dur: Int = 60,          // 按住／滑動時間 ms
    var delay: Int = 500,       // 這一步做完後等待 ms
    var cond: Boolean = false,  // 顏色條件
    var cx: Int = 0, var cy: Int = 0,
    var color: Int = 0,
    var tol: Int = 24,          // 每個色版容許差異 0–255
    var onFail: String = SKIP,  // skip | wait
    var waitMax: Int = 10000    // 等待顏色出現的上限 ms
) {
    companion object {
        const val TAP = "tap"; const val LONG = "long"; const val SWIPE = "swipe"; const val WAIT = "wait"
        const val SKIP = "skip"; const val WAIT_COLOR = "wait"

        fun from(o: JSONObject) = Step(
            o.optString("type", TAP), o.optInt("x"), o.optInt("y"), o.optInt("x2"), o.optInt("y2"),
            o.optInt("dur", 60), o.optInt("delay", 500), o.optBoolean("cond"), o.optInt("cx"), o.optInt("cy"),
            o.optInt("color"), o.optInt("tol", 24), o.optString("onFail", SKIP), o.optInt("waitMax", 10000)
        )
    }

    fun json(): JSONObject = JSONObject()
        .put("type", type).put("x", x).put("y", y).put("x2", x2).put("y2", y2)
        .put("dur", dur).put("delay", delay).put("cond", cond).put("cx", cx).put("cy", cy)
        .put("color", color).put("tol", tol).put("onFail", onFail).put("waitMax", waitMax)

    fun typeName() = when (type) {
        TAP -> "點擊"; LONG -> "長按"; SWIPE -> "滑動"; else -> "等待"
    }

    /** 每一步都有位置：等待步驟的位置用來判斷顏色 */
    fun hasPoint() = true
}

class Script(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "新腳本",
    val steps: MutableList<Step> = mutableListOf(),
    var loops: Int = 0,         // 0 = 無限
    var maxMin: Int = 0,        // 0 = 不限時間（分鐘）
    var jitterPx: Int = 0,      // 隨機座標偏移
    var jitterMs: Int = 0       // 隨機時間偏移
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("loops", loops).put("maxMin", maxMin).put("jitterPx", jitterPx).put("jitterMs", jitterMs)
        .put("steps", JSONArray().apply { steps.forEach { put(it.json()) } })

    companion object {
        fun from(o: JSONObject): Script {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            return Script(
                o.optString("id", UUID.randomUUID().toString()), o.optString("name", "腳本"),
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Step::from) }.toMutableList(),
                o.optInt("loops"), o.optInt("maxMin"), o.optInt("jitterPx"), o.optInt("jitterMs")
            )
        }
    }
}

object Scripts {
    private const val K = "ac_scripts"
    const val K_INJECTOR = "ac_injector"   // shizuku | a11y

    fun all(): MutableList<Script> {
        val arr = runCatching { JSONArray(Prefs.str(K, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Script::from) }.toMutableList()
    }

    fun saveAll(list: List<Script>) = Prefs.put(K, JSONArray().apply { list.forEach { put(it.json()) } }.toString())

    fun get(id: String) = all().firstOrNull { it.id == id }

    fun save(s: Script) {
        val list = all()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        saveAll(list)
    }

    fun delete(id: String) = saveAll(all().filter { it.id != id })

    fun injector() = Prefs.str(K_INJECTOR, "shizuku")
}
