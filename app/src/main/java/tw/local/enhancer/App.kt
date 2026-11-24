package tw.local.enhancer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.lsposed.hiddenapibypass.HiddenApiBypass
import tw.local.enhancer.core.Shell
import tw.local.enhancer.feature.photoguard.PhotoGuard
import tw.local.enhancer.feature.strict.StrictMode
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        }
        createChannels()
        Shell.init()
        // Shizuku 連上時，把之前沒能還原的狀態補還原（例如重開機後 Shizuku 晚啟動）
        runCatching {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
                bg { if (Shell.ready()) PhotoGuard.restoreIfStale(this) }
                if (Shell.ready()) tw.local.enhancer.feature.timeline.TimelineShizuku.onShizukuReady(this)
                if (Shell.ready()) tw.local.enhancer.feature.sys.SysTools.onShizukuReady()
            }
        }
        // 上次若在守護中被殺掉，開啟時先把狀態還原，避免相簿一直斷網／ADB 一直關著
        bg {
            PhotoGuard.restoreIfStale(this)
            StrictMode.restoreIfStale(this)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "背景服務", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_PHOTO, "相簿守護狀態", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_STRICT, "嚴格模式狀態", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        lateinit var app: App
            private set
        const val CH_SERVICE = "service"
        const val CH_PHOTO = "photo_guard"
        const val CH_STRICT = "strict_mode"

        val main = Handler(Looper.getMainLooper())
        private val pool = Executors.newFixedThreadPool(4)

        fun bg(block: () -> Unit) { pool.execute { runCatching(block) } }
        fun ui(block: () -> Unit) { main.post(block) }
        fun ui(delayMs: Long, block: () -> Unit) { main.postDelayed(block, delayMs) }
    }
}
