package com.assistant.adapter.watchdog
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

class WatchdogAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_watchdog",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("Watchdog heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val watchdogHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {

            AdapterHealthRegistry.getAll().forEach { snapshot ->

                val status =
                    AdapterHealthRegistry.effectiveStatus(snapshot.adapterName)

                when (status) {

                    "OFFLINE" -> RuntimeLogger.log(
                        "WATCHDOG OFFLINE: ${snapshot.adapterName}",
                        "WATCHDOG"
                    )

                    "DEGRADED" -> RuntimeLogger.log(
                        "WATCHDOG DEGRADED: ${snapshot.adapterName}",
                        "WATCHDOG"
                    )
                }
            }

            watchdogHandler.postDelayed(this, 15000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("WatchdogAdapterService started", "ADAPTER")
        val channel = NotificationChannel("watchdog_adapter", "Watchdog Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "watchdog_adapter")
            .setContentTitle("Splendor Watchdog Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

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
        RuntimeLogger.log("Watchdog heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
