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

class BootAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))
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
            RuntimeLogger.log("InputAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("InputAdapterService started", "ADAPTER")
        val channel = NotificationChannel("input_adapter", "Input Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "input_adapter")
            .setContentTitle("Splendor Input Node")
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
        RuntimeLogger.log("InputAdapter heartbeat scheduler started", "HEALTH")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        RuntimeLogger.log("InputAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
