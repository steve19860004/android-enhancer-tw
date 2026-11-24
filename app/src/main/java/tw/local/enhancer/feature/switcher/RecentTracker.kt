package tw.local.enhancer.feature.switcher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import tw.local.enhancer.core.Apps
import tw.local.enhancer.core.EnhancerA11yService
import tw.local.enhancer.core.Perms
import tw.local.enhancer.core.Prefs

/** 最近使用的 App：優先用無障礙即時追蹤，不足時用「使用情況存取」補。 */
object RecentTracker {
    const val K_EXCLUDE = "sw_exclude"
    private const val K_RECENT = "rt_recent"
    private const val MAX_KEEP = 40

    private val recent = ArrayList<String>()
    private var loaded = false
    private var lastSave = 0L

    private fun load() {
        if (loaded) return
        loaded = true
        recent.clear()
        recent.addAll(Prefs.str(K_RECENT).split(',').filter { it.isNotBlank() })
    }

    @Synchronized
    fun onForeground(ctx: Context, pkg: String) {
        load()
        recent.remove(pkg)
        recent.add(0, pkg)
        while (recent.size > MAX_KEEP) recent.removeAt(recent.size - 1)
        val now = System.currentTimeMillis()
        if (now - lastSave > 3000) {
            lastSave = now
            Prefs.put(K_RECENT, recent.joinToString(","))
        }
    }

    fun defaultExcludes(ctx: Context): Set<String> =
        Apps.launchers(ctx) + setOf("com.android.systemui", ctx.packageName)

    @Synchronized
    fun list(ctx: Context, max: Int): List<String> {
        load()
        val exclude = Prefs.set(K_EXCLUDE) + defaultExcludes(ctx) + Apps.imePackages(ctx)
        val current = EnhancerA11yService.instance?.foregroundPkg
        val merged = LinkedHashSet<String>()
        merged.addAll(recent)
        if (merged.size < max + 5 && Perms.usage(ctx)) merged.addAll(fromUsage(ctx))
        return merged.asSequence()
            .filter { it !in exclude && it != current }
            .filter { Apps.hasLaunch(ctx, it) }
            .take(max)
            .toList()
    }

    private fun fromUsage(ctx: Context): List<String> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 24 * 3600_000L, end)
        val e = UsageEvents.Event()
        val order = ArrayList<String>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                order.remove(e.packageName)
                order.add(0, e.packageName)
            }
        }
        return order
    }
}
