package com.assistant.adapter.stutter

// V3 ADMIN-WIRED
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import com.assistant.admin.AdminConfigStore
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * V3: the admin store is loaded in THIS process so every saved admin value
 * is actually obeyed by the stutter engines; the new PanelWatchEngine keeps
 * the radar's screen-beat truthful the instant an adaptive panel switches
 * rhythm. Heartbeat carries live burst truth.
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
                        " bursts/min=" + StutterPulseEngine.burstsPerMin +
                        " panel=" + StutterPulseEngine.panelHz + "Hz"
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
        RuntimeLogger.log("StutterAdapterService started - BURST RADAR V3", "ADAPTER")

        val channel = NotificationChannel("stutter_adapter", "Stutter Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        startForeground(9993, Notification.Builder(this, "stutter_adapter")
            .setContentTitle("Splendor Stutter Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build())

        // ---- STUTTER ENGINE STACK IGNITION [V3 ADMIN-WIRED] ----
        // CRITICAL: load the admin store in THIS process so every saved
        // admin value is actually obeyed by the stutter engines.
        AdminConfigStore.initialize(this)
        PerformanceTelemetryRegistry.initialize(this)
        StutterPulseEngine.detectPanel(this)
        StutterPulseEngine.start()
        PanelWatchEngine.start(this)
        BurstForensicsEngine.startDecay()
        RuntimeLogger.log("Stutter engine stack ignited: 3 engines [V3 ADMIN-WIRED]", "STUTTER")

        heartbeatHandler.post(heartbeatRunnable)
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        StutterPulseEngine.stop()
        PanelWatchEngine.stop()
        BurstForensicsEngine.stopDecay()
        RuntimeLogger.log("StutterAdapter stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
