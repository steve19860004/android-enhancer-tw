package tw.local.enhancer.core

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import tw.local.enhancer.feature.photoguard.PhotoGuard
import tw.local.enhancer.feature.strict.StrictMode
import tw.local.enhancer.feature.switcher.RecentTracker
import tw.local.enhancer.feature.timeline.TimelineFlow
import tw.local.enhancer.feature.tools.PowerCamera
import tw.local.enhancer.feature.translate.ScrollTranslator
import tw.local.enhancer.feature.volume.VolumeBooster
import tw.local.enhancer.feature.voice.VoiceIntercept

/**
 * 單一無障礙服務，把前景切換事件分派給各模組。
 * 不保存、不上傳任何畫面內容。
 */
class EnhancerA11yService : AccessibilityService() {

    companion object {
        @Volatile var instance: EnhancerA11yService? = null
            private set
    }

    /** 目前前景（真正的 Activity）套件名 */
    @Volatile var foregroundPkg: String = ""
        private set

    private var ignorePkgs: Set<String> = emptySet()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    PowerCamera.onScreen(this@EnhancerA11yService, false)
                    ScrollTranslator.stop()
                    StrictMode.onScreenOff(this@EnhancerA11yService)
                }
                Intent.ACTION_SCREEN_ON -> PowerCamera.onScreen(this@EnhancerA11yService, true)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ignorePkgs = Apps.imePackages(this) + setOf("com.android.systemui")
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, f)
        TimelineFlow.attach(this)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        // 浮層型助理有時只送「視窗清單變動」且沒有套件名，要自己掃一次視窗
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            (VoiceIntercept.enabled() || VoiceIntercept.capturing())
        ) scanWindows()
        TimelineFlow.onEvent(e)
        val pkg = e.packageName?.toString() ?: return
        VoiceIntercept.onAnyPackage(this, pkg)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = e.className?.toString()
            // 語音助理多半是浮層而不是 Activity，所以吃原始事件
            VoiceIntercept.onWindow(this, pkg, cls)
            if (pkg !in ignorePkgs && pkg != foregroundPkg && Apps.isActivity(this, pkg, cls)) {
                val prev = foregroundPkg
                foregroundPkg = pkg
                onForeground(prev, pkg)
            }
        }
        ScrollTranslator.onEvent(this, e)
    }

    private fun scanWindows() {
        val ws = runCatching { windows }.getOrNull() ?: return
        for (w in ws) {
            val p = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
            VoiceIntercept.onAnyPackage(this, p)
            if (VoiceIntercept.isTarget(p)) {
                VoiceIntercept.onWindow(this, p, null)
                break
            }
        }
    }

    private fun onForeground(prev: String, pkg: String) {
        RecentTracker.onForeground(this, pkg)
        PhotoGuard.onForeground(this, pkg)
        StrictMode.onForeground(this, prev, pkg)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!VolumeBooster.enabled() || !tw.local.enhancer.core.Prefs.bool(VolumeBooster.K_KEYHOOK)) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // 媒體音量已最大，再按音量＋ → 攔下來改成往上加增益
                if (VolumeBooster.mediaAtMax(this)) {
                    if (event.action == KeyEvent.ACTION_DOWN) VolumeBooster.bump(this, VolumeBooster.STEP_MB)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 有增益時先把增益降回來，降到 0 再恢復正常調音量
                if (VolumeBooster.gainMb() > 0) {
                    if (event.action == KeyEvent.ACTION_DOWN) VolumeBooster.bump(this, -VolumeBooster.STEP_MB)
                    return true
                }
                return false
            }
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance == null) return
        ScrollTranslator.stop()
        TimelineFlow.detach(this)
        runCatching { unregisterReceiver(screenReceiver) }
        instance = null
    }
}
