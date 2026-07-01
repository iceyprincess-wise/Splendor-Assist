package com.assistant.adapter.memory
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

class MemoryAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_memory",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("MemoryAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val memoryHandler = Handler(Looper.getMainLooper())

    private val memoryRunnable = object : Runnable {

        override fun run() {

            try {

                val activityManager =
                    getSystemService(Context.ACTIVITY_SERVICE)
                        as ActivityManager

                val info =
                    ActivityManager.MemoryInfo()

                activityManager.getMemoryInfo(info)

                val availableMb =
                    info.availMem / (1024 * 1024)

                RuntimeLogger.log(
                    "MEMORY available=${availableMb}MB lowMemory=${info.lowMemory}",
                    "MEMORY"
                )

            } catch (e: Exception) {

                RuntimeLogger.log(
                    "MEMORY telemetry failed",
                    "MEMORY"
                )
            }

            memoryHandler.postDelayed(this, 30000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("MemoryAdapterService started", "ADAPTER")
        val channel = NotificationChannel("memory_adapter", "Memory Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "memory_adapter")
            .setContentTitle("Splendor Memory Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_memory",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("MemoryAdapter heartbeat scheduler started", "HEALTH")

        memoryHandler.post(memoryRunnable)
        RuntimeLogger.log("Memory telemetry started", "MEMORY")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        memoryHandler.removeCallbacks(memoryRunnable)
        RuntimeLogger.log("MemoryAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
