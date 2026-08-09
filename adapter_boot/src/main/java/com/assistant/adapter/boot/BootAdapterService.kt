package com.assistant.adapter.boot
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
import android.os.SystemClock

class BootAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastState = "UNKNOWN"

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_boot",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "state=$lastState uptime=${SystemClock.elapsedRealtime() / 1000}s"
                )
            )
            RuntimeLogger.log("BootAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val bootHandler = Handler(Looper.getMainLooper())

    private val bootRunnable = object : Runnable {

        override fun run() {

            val uptimeSeconds =
                SystemClock.elapsedRealtime() / 1000

            val stabilization =
                when {
                    uptimeSeconds < 60 -> "EARLY_BOOT"
                    uptimeSeconds < 300 -> "STABILIZING"
                    else -> "STABLE"
                }
            lastState = stabilization

            RuntimeLogger.log(
                "BOOT uptime=${uptimeSeconds}s state=$stabilization",
                "BOOT"
            )

            bootHandler.postDelayed(this, 30000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("BootAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (e)) - replaces the
        // per-node row on ID 9993, which collided with input/battery/lag.
        NodeNotificationHub.attach(this, "adapter_boot")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_boot",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("BootAdapter heartbeat scheduler started", "HEALTH")

        bootHandler.post(bootRunnable)
        RuntimeLogger.log("Boot telemetry started", "BOOT")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        bootHandler.removeCallbacks(bootRunnable)
        NodeNotificationHub.detach(this, "adapter_boot")
        RuntimeLogger.log("BootAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
