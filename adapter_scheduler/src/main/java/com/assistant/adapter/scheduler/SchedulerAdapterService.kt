package com.assistant.adapter.scheduler
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.survival.ResourceBudgetRegistry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

class SchedulerAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_scheduler",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("Scheduler heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val schedulerHandler = Handler(Looper.getMainLooper())

    private val schedulerRunnable = object : Runnable {
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

            
ResourceBudgetRegistry.update(
    active,
    degraded,
    offline
)

            RuntimeLogger.log(
                "FLEET HEALTH active=$active degraded=$degraded offline=$offline",
                "SCHEDULER"
            )

            schedulerHandler.postDelayed(this, 15000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("SchedulerAdapterService started", "ADAPTER")
        val channel = NotificationChannel("scheduler_adapter", "Scheduler Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "scheduler_adapter")
            .setContentTitle("Splendor Scheduler Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_scheduler",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("Scheduler heartbeat scheduler started", "HEALTH")

        schedulerHandler.post(schedulerRunnable)
        RuntimeLogger.log("Scheduler fleet monitor started", "SCHEDULER")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        schedulerHandler.removeCallbacks(schedulerRunnable)
        RuntimeLogger.log("Scheduler heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
