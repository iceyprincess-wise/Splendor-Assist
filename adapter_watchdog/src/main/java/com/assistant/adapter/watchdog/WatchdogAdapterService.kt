package com.assistant.adapter.watchdog
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.survival.ProcessSurvivalRegistry

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

class WatchdogAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastScan = "no scan yet"

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_watchdog",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "scan: $lastScan"
                )
            )
            RuntimeLogger.log("Watchdog heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val watchdogHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {

            var offline = 0
            var degraded = 0

            AdapterHealthRegistry.getAll().forEach { snapshot ->

                val status =
                    AdapterHealthRegistry.effectiveStatus(snapshot.adapterName)

                ProcessSurvivalRegistry.update(
                    snapshot.adapterName,
                    status
                )

                when (status) {

                    "OFFLINE" -> {
                        offline++
                        RuntimeLogger.log(
                            "WATCHDOG OFFLINE: ${snapshot.adapterName}",
                            "WATCHDOG"
                        )
                    }

                    "DEGRADED" -> {
                        degraded++
                        RuntimeLogger.log(
                            "WATCHDOG DEGRADED: ${snapshot.adapterName}",
                            "WATCHDOG"
                        )
                    }
                }
            }

            lastScan = "offline=$offline degraded=$degraded"

            watchdogHandler.postDelayed(this, 15000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("WatchdogAdapterService started", "ADAPTER")

        // Unified foundation notification (Task C item (e) completion) -
        // this node was the NINTH service on colliding foreground ID 9993;
        // it was missed in the first conversion batches.
        NodeNotificationHub.attach(this, "adapter_watchdog")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_watchdog",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("Watchdog heartbeat scheduler started", "HEALTH")

        watchdogHandler.post(watchdogRunnable)
        RuntimeLogger.log("Watchdog scanner started", "WATCHDOG")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        watchdogHandler.removeCallbacks(watchdogRunnable)
        NodeNotificationHub.detach(this, "adapter_watchdog")
        RuntimeLogger.log("Watchdog heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
