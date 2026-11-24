package tw.local.enhancer.tiles

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import tw.local.enhancer.feature.switcher.FloatingService
import tw.local.enhancer.feature.translate.ScrollTranslator
import tw.local.enhancer.ui.TriggerActivity

abstract class BaseTile : TileService() {
    protected fun launchTrigger(action: String) {
        val i = Intent(this, TriggerActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    protected fun setState(on: Boolean, sub: String? = null) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29 && sub != null) t.subtitle = sub
        t.updateTile()
    }
}

class FanServiceTile : BaseTile() {
    override fun onStartListening() = setState(FloatingService.running, if (FloatingService.running) "執行中" else "已關閉")
    override fun onClick() = launchTrigger(TriggerActivity.ACTION_TOGGLE_SWITCHER)
}

class FanExpandTile : BaseTile() {
    override fun onStartListening() = setState(false, "點擊展開")
    override fun onClick() = launchTrigger(TriggerActivity.ACTION_EXPAND)
}

class ScrollTranslateTile : BaseTile() {
    override fun onStartListening() = setState(ScrollTranslator.active, if (ScrollTranslator.active) "翻譯中" else "點擊啟動")
    override fun onClick() = launchTrigger(TriggerActivity.ACTION_TRANSLATE)
}

class CircleSearchTile : BaseTile() {
    override fun onStartListening() = setState(false, "Shizuku")
    override fun onClick() = launchTrigger(TriggerActivity.ACTION_CIRCLE)
}
