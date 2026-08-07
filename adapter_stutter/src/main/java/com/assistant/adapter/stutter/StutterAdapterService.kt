package com.assistant.adapter.stutter

// V2 BURST
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * V2: the hardcoded-60Hz spike logger is gone (panel budget is detected),
 * per-spike log spam is gone (forensics aggregates), and the dead vector
 * math is gone. This node now runs the burst radar + forensics and its
 * heartbeat carries live burst truth.
 */
class StutterAdapterService : Service() {

    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_stutter",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "burst=" + BurstForensicsEngine.state +
                        " bursts/min=" + StutterPulseEngine.burstsPerMin
                )
            )
            RuntimeLogger.log("StutterAdapter heartbeat burst=" +
                BurstForensicsEngine.state, "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        RuntimeLogger.log("StutterAdapterService started - BURST RADAR V2", "ADAPTER")

        val channel = NotificationChannel("stutter_adapter", "Stutter Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        startForeground(9993, Notification.Builder(this, "stutter_adapter")
            .setContentTitle("Splendor Stutter Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build())

        PerformanceTelemetryRegistry.initialize(this)
        StutterPulseEngine.detectPanel(this)
        StutterPulseEngine.start()
        BurstForensicsEngine.startDecay()
        RuntimeLogger.log("Stutter engine stack ignited: burst radar + forensics [V2 BURST]", "STUTTER")

        heartbeatHandler.post(heartbeatRunnable)
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        StutterPulseEngine.stop()
        RuntimeLogger.log("StutterAdapter stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
