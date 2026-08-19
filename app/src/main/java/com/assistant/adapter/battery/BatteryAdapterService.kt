package com.assistant.adapter.battery

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Process
import com.assistant.adapter.smartassist.VisionTrust
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

class BatteryAdapterService : Service() {
    // Keep messenger on main looper for binding if needed by system
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    
    // CRITICAL FIX: Move ALL polling off main thread to eliminate UI jank and secure 15fps baseline
    private val workerThread = HandlerThread("BatteryAdapterWorker", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    @Volatile private var lastLevel = -1
    @Volatile private var lastStatus = -1
    @Volatile private var lastPollTime = System.currentTimeMillis()
    @Volatile private var isGameActive = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_battery",
                    status = if (isGameActive) "ACTIVE_GAMEPLAY" else "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = 0,
                    details = "level=${lastLevel}% status=$lastStatus game=$isGameActive"
                )
            )
            RuntimeLogger.log("BatteryAdapter heartbeat", "HEALTH")
            workerHandler.postDelayed(this, 10000)
        }
    }

    private val batteryRunnable = object : Runnable {
        override fun run() {
            try {
                val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

                // Calculate true percentage (scale is usually 100, but sometimes different)
                val pct = if (scale > 0) (level * 100) / scale else level
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastPollTime
                lastPollTime = currentTime

                // Calculate drain rate to detect Helio G81 voltage sag/thermal throttle under gaming load
                // If CPU boosts hard, battery drains fast. We catch this to prevent thermal throttling stutters.
                val drainRate = if (lastLevel > 0 && deltaTime > 0) {
                    ((lastLevel - pct) * 60000f) / deltaTime.toFloat()
                } else 0f

                val isCharging = charging == BatteryManager.BATTERY_STATUS_CHARGING ||
                    charging == BatteryManager.BATTERY_STATUS_FULL

                // Preserve original bus publish (using pct for safer threshold checks across devices)
                AdapterSignalBus.publishBattery(pct, isCharging)

                // Companion Engine: React to gameplay power demands
                GameplayPowerEngine.evaluate(pct, isCharging, drainRate)

                lastLevel = pct
                lastStatus = charging

                val battLabel = if (pct < 15 && !isCharging) " *** BATTERY CRITICAL ***" else ""
                val sagLabel = if (drainRate > 1.5f && !isCharging && isGameActive) " [VOLTAGE SAG]" else ""
                RuntimeLogger.log(
                    "BATTERY pct=${pct}% status=$charging charging=$isCharging drain=${"%.2f".format(drainRate)}/min$battLabel$sagLabel",
                    "BATTERY"
                )

            } catch (e: Exception) {
                RuntimeLogger.log("BATTERY telemetry failed: ${e.message}", "BATTERY")
            }

            // Dynamic polling: 5s during game to catch thermal sags fast, 30s otherwise to save power
            val nextDelay = if (isGameActive) 5000L else 30000L
            workerHandler.postDelayed(this, nextDelay)
        }
    }

    private val gameStateRunnable = object : Runnable {
        override fun run() {
            isGameActive = VisionTrust.isGameForeground()
            workerHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("BatteryAdapterService started", "ADAPTER")

        NodeNotificationHub.attach(this, "adapter_battery")

        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_battery",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Foreground service running (Worker thread active)"
            )
        )

        workerHandler.post(heartbeatRunnable)
        workerHandler.post(batteryRunnable)
        workerHandler.post(gameStateRunnable)
        
        RuntimeLogger.log("Battery telemetry & GameplayPowerEngine started on worker thread", "BATTERY")
    }

    override fun onDestroy() {
        workerHandler.removeCallbacks(heartbeatRunnable)
        workerHandler.removeCallbacks(batteryRunnable)
        workerHandler.removeCallbacks(gameStateRunnable)
        workerThread.quitSafely()
        NodeNotificationHub.detach(this, "adapter_battery")
        RuntimeLogger.log("BatteryAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder

    /**
     * COMPANION ENGINE: GameplayPowerEngine
     * Purpose: Detects eFootball 2027 foreground state via VisionTrust, mitigates Helio G81 thermal throttle,
     * and signals CPU/LoadShed engines to maintain strict 15fps smoothness and fast input.
     */
    object GameplayPowerEngine {
        fun evaluate(level: Int, isCharging: Boolean, drainRate: Float) {
            // If battery is critically sagging under load, signal the LoadShed governor 
            // to kill background tasks, preserving CPU cycles for eFootball 15fps.
            // Helio G81-Ultra draws heavy current during boost; rapid drain = thermal limit approaching.
            if (drainRate > 1.5f && !isCharging) {
                val brakeLevel = if (drainRate > 3.0f) 2 else 1
                AdapterSignalBus.publishExecutionBrake(brakeLevel)
                RuntimeLogger.log("GameplayPowerEngine: Drain=${"%.2f".format(drainRate)}/min -> ExecutionBrake=$brakeLevel", "ENGINE")
            } else if (AdapterSignalBus.executionIsBraked) {
                // Release brake if charging or drain normalizes
                AdapterSignalBus.publishExecutionBrake(0)
            }
        }
    }
}
