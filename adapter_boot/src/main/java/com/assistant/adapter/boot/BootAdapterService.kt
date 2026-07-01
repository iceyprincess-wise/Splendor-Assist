package com.assistant.adapter.boot
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
import android.os.SystemClock

class BootAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_boot",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
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
        val channel = NotificationChannel("boot_adapter", "Boot Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "boot_adapter")
            .setContentTitle("Splendor Boot Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

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
        RuntimeLogger.log("BootAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
