package com.assistant.adapter.input
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

class InputAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_input",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("InputAdapter heartbeat", "HEALTH")
            try { GestureTimingFeedbackEngine.checkExpiry() } catch (_: Throwable) {}
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("InputAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (c)) - replaces the
        // per-node "Splendor Input Node" row on ID 9993, which collided
        // with the memory node's ID.
        NodeNotificationHub.attach(this, "adapter_input")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_input",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        InputPriorityEngine.start()
        InputLatencyEngine.start()
        TouchQualityEngine.start()
        OomAdaptiveThrottleEngine.start()
        GestureTimingFeedbackEngine.reset()
        InputThermalEliminatorEngine.start(applicationContext)
        InputVsyncEliminatorEngine.start()
        RuntimeLogger.log("Input engine stack ignited: 7 engines [LATENCY+QUALITY+PRIORITY+OOM+GESTURE+THERMAL+VSYNC]", "INPUT")
        RuntimeLogger.log("InputAdapter heartbeat scheduler started", "HEALTH")
    }


    override fun onDestroy() {
        InputLatencyEngine.stop()
        TouchQualityEngine.stop()
        InputPriorityEngine.stop()
        OomAdaptiveThrottleEngine.stop()
        GestureTimingFeedbackEngine.reset()
        InputThermalEliminatorEngine.stop()
        InputVsyncEliminatorEngine.stop()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        NodeNotificationHub.detach(this, "adapter_input")
        RuntimeLogger.log("InputAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
