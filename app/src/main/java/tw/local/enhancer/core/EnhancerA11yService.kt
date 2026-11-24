package tw.local.enhancer.core

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import tw.local.enhancer.feature.photoguard.PhotoGuard
import tw.local.enhancer.feature.strict.StrictMode
import tw.local.enhancer.feature.switcher.RecentTracker
import tw.local.enhancer.feature.tools.PowerCamera
import tw.local.enhancer.feature.translate.ScrollTranslator
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
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
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

    private fun onForeground(prev: String, pkg: String) {
        RecentTracker.onForeground(this, pkg)
        PhotoGuard.onForeground(this, pkg)
        StrictMode.onForeground(this, prev, pkg)
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
        runCatching { unregisterReceiver(screenReceiver) }
        instance = null
    }
}
