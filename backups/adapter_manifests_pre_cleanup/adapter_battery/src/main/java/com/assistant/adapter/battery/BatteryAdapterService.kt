package com.assistant.adapter.battery
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

class BatteryAdapterService : Service() {
    private val messenger = Messenger(Handler(Handler.Callback { msg -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_battery",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "Heartbeat active"
                )
            )
            RuntimeLogger.log("BatteryAdapter heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }


    private val batteryHandler = Handler(Looper.getMainLooper())

    private val batteryRunnable = object : Runnable {

        override fun run() {

            try {

                val intent = registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )

                val level =
                    intent?.getIntExtra(
                        BatteryManager.EXTRA_LEVEL,
                        -1
                    ) ?: -1

                val charging =
                    intent?.getIntExtra(
                        BatteryManager.EXTRA_STATUS,
                        -1
                    )

                RuntimeLogger.log(
                    "BATTERY level=${level}% status=$charging",
                    "BATTERY"
                )

            } catch (e: Exception) {

                RuntimeLogger.log(
                    "BATTERY telemetry failed",
                    "BATTERY"
                )
            }

            batteryHandler.postDelayed(this, 30000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("BatteryAdapterService started", "ADAPTER")
        val channel = NotificationChannel("battery_adapter", "Battery Core", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "battery_adapter")
            .setContentTitle("Splendor Battery Node")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(9993, notification)

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_battery",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running"
            )
        )

        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("BatteryAdapter heartbeat scheduler started", "HEALTH")

        batteryHandler.post(batteryRunnable)
        RuntimeLogger.log("Battery telemetry started", "BATTERY")
    }


    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        batteryHandler.removeCallbacks(batteryRunnable)
        RuntimeLogger.log("BatteryAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
