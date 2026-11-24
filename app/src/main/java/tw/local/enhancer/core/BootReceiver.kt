package tw.local.enhancer.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tw.local.enhancer.App
import tw.local.enhancer.feature.photoguard.PhotoGuard
import tw.local.enhancer.feature.strict.StrictMode
import tw.local.enhancer.feature.switcher.FloatingService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        App.bg {
            try {
                PhotoGuard.restoreIfStale(ctx)
                StrictMode.restoreIfStale(ctx)
            } finally {
                pending.finish()
            }
        }
        tw.local.enhancer.feature.timeline.TimelineShizuku.scheduleDaily(ctx)
        if (Prefs.bool(FloatingService.K_ENABLED) && Perms.overlay(ctx)) {
            runCatching { FloatingService.start(ctx) }
        }
    }
}
