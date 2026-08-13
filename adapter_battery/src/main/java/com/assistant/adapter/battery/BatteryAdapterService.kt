package com.assistant.adapter.battery
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger
import com.assistant.diagnostic.AdapterSignalBus

class BatteryAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastLevel = -1
    @Volatile private var lastStatus = -1

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_battery",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "level=${lastLevel}% status=$lastStatus"
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

                lastLevel = level
                lastStatus = charging ?: -1
                // PHASE3: publish to bus so engines can react to low battery
                val isCharging = charging == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    charging == android.os.BatteryManager.BATTERY_STATUS_FULL
                AdapterSignalBus.publishBattery(level, isCharging)

                val battLabel = if (level < 15 && !isCharging) " *** BATTERY CRITICAL ***" else ""
                RuntimeLogger.log(
                    "BATTERY level=${level}% status=$charging charging=$isCharging$battLabel",
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

        // Unified foundation notification (Task C item (e)) - replaces the
        // per-node row on ID 9993, which collided with input/boot/lag.
        NodeNotificationHub.attach(this, "adapter_battery")

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
        NodeNotificationHub.detach(this, "adapter_battery")
        RuntimeLogger.log("BatteryAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
