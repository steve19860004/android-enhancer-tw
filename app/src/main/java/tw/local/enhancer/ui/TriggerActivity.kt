package tw.local.enhancer.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import tw.local.enhancer.App
import tw.local.enhancer.core.Perms
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.feature.switcher.FloatingService
import tw.local.enhancer.feature.tools.CircleSearch
import tw.local.enhancer.feature.translate.ScrollTranslator

/** 透明跳板：桌面捷徑、快捷磁貼都從這裡進來，做完就關掉。 */
class TriggerActivity : Activity() {
    companion object {
        const val ACTION_EXPAND = "tw.local.enhancer.action.EXPAND"
        const val ACTION_TOGGLE_SWITCHER = "tw.local.enhancer.action.TOGGLE_SWITCHER"
        const val ACTION_TRANSLATE = "tw.local.enhancer.action.TRANSLATE"
        const val ACTION_CIRCLE = "tw.local.enhancer.action.CIRCLE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = applicationContext
        when (intent?.action) {
            ACTION_EXPAND -> {
                if (!Perms.overlay(this)) {
                    Toast.makeText(this, "請先授權「顯示在其他應用程式上層」", Toast.LENGTH_LONG).show()
                    Perms.openOverlay(this)
                } else {
                    // 在前景時就啟動服務（背景啟動前景服務會被系統擋），服務內部會延遲展開
                    runCatching { FloatingService.start(ctx, FloatingService.ACTION_EXPAND) }
                }
            }
            ACTION_TOGGLE_SWITCHER -> {
                if (FloatingService.running) {
                    Prefs.put(FloatingService.K_ENABLED, false)
                    FloatingService.stop(ctx)
                } else if (Perms.overlay(this)) {
                    Prefs.put(FloatingService.K_ENABLED, true)
                    runCatching { FloatingService.start(ctx) }
                } else {
                    Perms.openOverlay(this)
                }
            }
            ACTION_TRANSLATE -> App.ui(450) { ScrollTranslator.toggle(ctx) }
            ACTION_CIRCLE -> App.ui(350) { CircleSearch.trigger(ctx) }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
