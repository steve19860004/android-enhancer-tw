package tw.local.enhancer.feature.callmusic

import android.app.Notification
import android.content.ComponentName
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import tw.local.enhancer.App
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell
import java.util.concurrent.Executor

/**
 * Tab 1：通訊軟體來電／通話中 → 暫停音樂；掛斷 → 恢復。
 * 判斷依據：通話通知（CATEGORY_CALL / CallStyle）＋ 系統音訊模式（IN_COMMUNICATION）。
 */
class CallMusicListener : NotificationListenerService() {

    companion object {
        const val K_ENABLED = "t1_enabled"
        const val K_COMM = "t1_comm"
        const val K_MUSIC = "t1_music"
        const val K_SHIZUKU = "t1_shizuku_resume"
        const val K_AUDIO_MODE = "t1_audio_mode"

        val DEFAULT_COMM = setOf(
            "jp.naver.line.android", "org.telegram.messenger", "com.facebook.orca", "com.whatsapp",
            "com.tencent.mm", "com.discord", "com.skype.raider", "com.google.android.apps.tachyon",
            "com.viber.voip", "com.kakao.talk", "us.zoom.videomeetings", "com.microsoft.teams"
        )

        fun commApps(): Set<String> = if (Prefs.has(K_COMM)) Prefs.set(K_COMM) else DEFAULT_COMM

        @Volatile var instance: CallMusicListener? = null
            private set
    }

    private val activeCallKeys = mutableSetOf<String>()
    private var modeInCall = false
    private var inCall = false
    private val pausedPkgs = mutableSetOf<String>()
    private var modeListener: Any? = null

    private val endRunnable = Runnable { onCallEnd() }

    override fun onListenerConnected() {
        instance = this
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AudioManager::class.java)
            val l = AudioManager.OnModeChangedListener { mode -> onAudioMode(mode) }
            am.addOnModeChangedListener(Executor { App.main.post(it) }, l)
            modeListener = l
        }
        // 已經在通話中就上線的情況
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onListenerDisconnected() {
        if (Build.VERSION.SDK_INT >= 31) {
            val l = modeListener as? AudioManager.OnModeChangedListener
            if (l != null) runCatching { getSystemService(AudioManager::class.java).removeOnModeChangedListener(l) }
        }
        instance = null
    }

    private fun isCallNotification(n: Notification): Boolean {
        if (n.category == Notification.CATEGORY_CALL) return true
        val template = n.extras?.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (template.contains("CallStyle")) return true
        // 部分 App（舊版 LINE 等）只標示常駐＋全螢幕來電畫面
        if (n.fullScreenIntent != null && (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return true
        return false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.bool(K_ENABLED)) return
        if (sbn.packageName !in commApps()) return
        if (!isCallNotification(sbn.notification)) return
        activeCallKeys.add(sbn.key)
        evaluate()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (activeCallKeys.remove(sbn.key)) evaluate()
    }

    private fun onAudioMode(mode: Int) {
        if (!Prefs.bool(K_AUDIO_MODE, true)) return
        modeInCall = mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_RINGTONE
        evaluate()
    }

    private fun evaluate() {
        val nowInCall = activeCallKeys.isNotEmpty() || modeInCall
        if (nowInCall && !inCall) {
            inCall = true
            App.main.removeCallbacks(endRunnable)
            onCallStart()
        } else if (!nowInCall && inCall) {
            inCall = false
            // 掛斷後稍等，讓通訊軟體釋放音訊焦點
            App.main.removeCallbacks(endRunnable)
            App.main.postDelayed(endRunnable, 1500)
        }
    }

    private fun controllers(): List<MediaController> = runCatching {
        getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(this, CallMusicListener::class.java))
    }.getOrDefault(emptyList())

    private fun musicAllowed(pkg: String): Boolean {
        if (pkg in commApps()) return false
        val sel = Prefs.set(K_MUSIC)
        return sel.isEmpty() || pkg in sel
    }

    private fun onCallStart() {
        if (!Prefs.bool(K_ENABLED)) return
        for (c in controllers()) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING && musicAllowed(c.packageName)) {
                runCatching { c.transportControls.pause() }
                pausedPkgs.add(c.packageName)
            }
        }
    }

    private fun onCallEnd() {
        if (inCall || pausedPkgs.isEmpty()) return
        val targets = pausedPkgs.toSet()
        pausedPkgs.clear()
        val cs = controllers()
        for (pkg in targets) {
            val c = cs.firstOrNull { it.packageName == pkg }
            if (c != null) runCatching { c.transportControls.play() }
        }
        if (Prefs.bool(K_SHIZUKU) && Shell.ready()) {
            // 播放器沒反應時，用媒體鍵再補一次
            App.main.postDelayed({
                val stillPaused = controllers().none { it.packageName in targets && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                if (stillPaused) App.bg { Shell.exec("input keyevent 126") }
            }, 1200)
        }
    }

    /** 給 UI 的狀態字串 */
    fun stateText(): String = if (inCall) "通話中（已暫停 ${pausedPkgs.size} 個播放器）" else "待命中"
}
