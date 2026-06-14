package com.assistant.adapter.stutter
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

class StutterAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))
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
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("StutterAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val stutterHandler = Handler(Looper.getMainLooper())

    private var lastFrameTick = System.currentTimeMillis()

    private val stutterRunnable = object : Runnable {

        override fun run() {

            val now = System.currentTimeMillis()

            val frameGap =
                now - lastFrameTick

            lastFrameTick = now

            RuntimeLogger.log(
                "STUTTER frameGap=${frameGap}ms",
                "STUTTER"
            )

            stutterHandler.postDelayed(this, 16)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("StutterAdapterService started", "ADAPTER")
        val channel = NotificationChannel("stutter_adapter", "Stutter Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "stutter_adapter")
            .setContentTitle("Splendor Stutter Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_stutter",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("StutterAdapter heartbeat scheduler started", "HEALTH")

        stutterHandler.post(stutterRunnable)
        RuntimeLogger.log("Stutter telemetry started", "STUTTER")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        stutterHandler.removeCallbacks(stutterRunnable)
        RuntimeLogger.log("StutterAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
