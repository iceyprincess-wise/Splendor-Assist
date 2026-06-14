package com.assistant.adapter.lag
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

class LagAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_lag",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("LagAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val lagHandler = Handler(Looper.getMainLooper())

    private var lastTick = System.currentTimeMillis()

    private val lagRunnable = object : Runnable {

        override fun run() {

            val now = System.currentTimeMillis()

            val drift =
                now - lastTick - 1000

            lastTick = now

            RuntimeLogger.log(
                "LAG drift=${drift}ms",
                "LAG"
            )

            lagHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("LagAdapterService started", "ADAPTER")
        val channel = NotificationChannel("lag_adapter", "Lag Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "lag_adapter")
            .setContentTitle("Splendor Lag Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_lag",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("LagAdapter heartbeat scheduler started", "HEALTH")

        lagHandler.post(lagRunnable)
        RuntimeLogger.log("Lag telemetry started", "LAG")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        lagHandler.removeCallbacks(lagRunnable)
        RuntimeLogger.log("LagAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
