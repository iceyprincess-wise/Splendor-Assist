package com.assistant.adapter.smartassist
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

class SmartAssistAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_smartassist",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("SmartAssist heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val smartAssistHandler = Handler(Looper.getMainLooper())

    private val smartAssistRunnable = object : Runnable {
        override fun run() {

            var active = 0
            var degraded = 0
            var offline = 0

            AdapterHealthRegistry.getAll().forEach { snapshot ->

                when (
                    AdapterHealthRegistry.effectiveStatus(
                        snapshot.adapterName
                    )
                ) {
                    "ACTIVE" -> active++
                    "DEGRADED" -> degraded++
                    "OFFLINE" -> offline++
                }
            }

            val recommendation =
                when {
                    offline > 0 ->
                        "RECOVERY RECOMMENDED"

                    degraded > 0 ->
                        "SYSTEM DEGRADED"

                    else ->
                        "SYSTEM HEALTHY"
                }

            RuntimeLogger.log(
                recommendation,
                "SMARTASSIST"
            )

            smartAssistHandler.postDelayed(this, 20000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("SmartAssistAdapterService started", "ADAPTER")
        val channel = NotificationChannel("smartassist_adapter", "SmartAssist Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "smartassist_adapter")
            .setContentTitle("Splendor SmartAssist Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_smartassist",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("SmartAssist heartbeat scheduler started", "HEALTH")

        smartAssistHandler.post(smartAssistRunnable)
        RuntimeLogger.log("SmartAssist decision engine started", "SMARTASSIST")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        smartAssistHandler.removeCallbacks(smartAssistRunnable)
        RuntimeLogger.log("SmartAssist heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
