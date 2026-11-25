package tw.local.enhancer.feature.mock

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import tw.local.enhancer.App
import tw.local.enhancer.core.Prefs
import tw.local.enhancer.core.Shell
import tw.local.enhancer.ui.MainActivity

/**
 * 假 GPS 定位（免 root）：用 Shizuku 把本 App 設成「模擬定位來源」(appops mock_location)，
 * 再用 LocationManager 的測試提供者持續送出指定座標。
 * 注意：違反多數 App／遊戲的服務條款，可能被封號，請自行評估。
 */
object MockLocation {
    const val K_ENABLED = "mock_enabled"
    const val K_LAT = "mock_lat"
    const val K_LNG = "mock_lng"

    @Volatile var running = false
        private set

    fun lat() = Prefs.str(K_LAT, "25.0330").toDoubleOrNull() ?: 25.0330
    fun lng() = Prefs.str(K_LNG, "121.5654").toDoubleOrNull() ?: 121.5654

    /** 用 Shizuku 把本 App 設成模擬定位來源（Android 6+ 用 appops，不需開發者選項選 App） */
    fun grantMockViaShizuku(ctx: Context): Boolean {
        if (!Shell.ready()) return false
        val r = Shell.exec("appops set ${ctx.packageName} android:mock_location allow")
        return r.ok
    }

    fun isMockGranted(ctx: Context): Boolean {
        if (!Shell.ready()) return false
        val out = Shell.exec("appops get ${ctx.packageName} android:mock_location").out
        return out.contains("allow", true)
    }

    fun start(ctx: Context) {
        Prefs.put(K_ENABLED, true)
        val i = Intent(ctx, MockService::class.java)
        ctx.startForegroundService(i)
    }

    fun stop(ctx: Context) {
        Prefs.put(K_ENABLED, false)
        ctx.stopService(Intent(ctx, MockService::class.java))
    }

    class MockService : Service() {
        private val lm by lazy { getSystemService(LocationManager::class.java) }
        private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        private var ticking = false
        private val tick = object : Runnable {
            override fun run() {
                if (!ticking) return
                push()
                App.main.postDelayed(this, 1000)
            }
        }

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onCreate() {
            super.onCreate()
            running = true
            goForeground()
            setup()
            ticking = true
            App.main.post(tick)
        }

        private fun goForeground() {
            val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, App.CH_SERVICE)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("假 GPS 定位執行中")
                .setContentText("座標 ${lat()}, ${lng()}")
                .setContentIntent(pi).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(14, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(14, n)
        }

        private fun setup() {
            for (p in providers) {
                runCatching {
                    lm.addTestProvider(p, false, false, false, false, true, true, true, 1, 1)
                    lm.setTestProviderEnabled(p, true)
                }
            }
        }

        private fun push() {
            val la = lat(); val ln = lng()
            for (p in providers) {
                runCatching {
                    val loc = Location(p).apply {
                        latitude = la; longitude = ln; accuracy = 1f
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        if (Build.VERSION.SDK_INT >= 26) bearingAccuracyDegrees = 0.1f
                        if (Build.VERSION.SDK_INT >= 26) verticalAccuracyMeters = 0.1f
                        if (Build.VERSION.SDK_INT >= 26) speedAccuracyMetersPerSecond = 0.01f
                        altitude = 10.0
                    }
                    lm.setTestProviderLocation(p, loc)
                }
            }
        }

        override fun onDestroy() {
            ticking = false
            App.main.removeCallbacks(tick)
            for (p in providers) {
                runCatching { lm.setTestProviderEnabled(p, false) }
                runCatching { lm.removeTestProvider(p) }
            }
            running = false
            super.onDestroy()
        }
    }
}
