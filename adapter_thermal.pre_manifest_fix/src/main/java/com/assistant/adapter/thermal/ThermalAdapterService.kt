package com.assistant.adapter.thermal
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

class ThermalAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_thermal",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("ThermalAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val thermalHandler = Handler(Looper.getMainLooper())

    private val thermalRunnable = object : Runnable {

        override fun run() {

            try {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                    val powerManager =
                        getSystemService(PowerManager::class.java)

                    val status =
                        powerManager?.currentThermalStatus ?: -1

                    RuntimeLogger.log(
                        "THERMAL status=$status",
                        "THERMAL"
                    )
                }

            } catch (e: Exception) {

                RuntimeLogger.log(
                    "THERMAL telemetry failed",
                    "THERMAL"
                )
            }

            thermalHandler.postDelayed(this, 30000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("ThermalAdapterService started", "ADAPTER")
        val channel = NotificationChannel("thermal_adapter", "Thermal Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "thermal_adapter")
            .setContentTitle("Splendor Thermal Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_thermal",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("ThermalAdapter heartbeat scheduler started", "HEALTH")

        thermalHandler.post(thermalRunnable)
        RuntimeLogger.log("Thermal telemetry started", "THERMAL")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        thermalHandler.removeCallbacks(thermalRunnable)
        RuntimeLogger.log("ThermalAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
