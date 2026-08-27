package com.assistant.adapter.watchdog

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.survival.ProcessSurvivalRegistry

import android.app.Service
import android.content.Context
import android.os.PowerManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Messenger

/**
 * PHASE3 UPGRADE: WatchdogAdapterService
 * Previous state: PASSIVE OBSERVER — detected OFFLINE nodes and logged them.
 *                 Never acted. Watching but not guarding.
 * Real job now: detects OFFLINE adapters and attempts restart via startService().
 * Android will throttle repeated restarts (crash-loop protection), but one
 * honest restart attempt is far better than zero.
 */
class WatchdogAdapterService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { _ -> true }))
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastScan = "no scan yet"
    @Volatile private var totalRestarts = 0
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    // Map from adapter registry name to its service class name
    private val adapterMap = mapOf(
        "adapter_net"          to "com.assistant.adapter.net.NetAdapterService",
        "adapter_input"        to "com.assistant.adapter.input.InputAdapterService",
        "adapter_lag"          to "com.assistant.adapter.lag.LagAdapterService",
        "adapter_stutter"      to "com.assistant.adapter.stutter.StutterAdapterService",
        "adapter_ping"         to "com.assistant.adapter.ping.PingAdapterService",
        "adapter_memory"       to "com.assistant.adapter.memory.MemoryAdapterService",
        "adapter_thermal"      to "com.assistant.adapter.thermal.ThermalAdapterService",
        "adapter_battery"      to "com.assistant.adapter.battery.BatteryAdapterService",
        "adapter_scheduler"    to "com.assistant.adapter.scheduler.SchedulerAdapterService",
        "adapter_boot"         to "com.assistant.adapter.boot.BootAdapterService",
        "adapter_sync"         to "com.assistant.adapter.sync.SyncAdapterService",
        "adapter_lmk"          to "com.assistant.adapter.lmk.LmkAdapterService",
        "adapter_interruption" to "com.assistant.adapter.interruption.InterruptionAdapterService",
        "adapter_smartassist"  to "com.assistant.adapter.smartassist.SmartAssistAdapterService"
    )

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_watchdog",
                    status = "ACTIVE",
                    lastHeartbeat = System.currentTimeMillis(),
                    errorCount = 0,
                    recoveryCount = totalRestarts,
                    details = "scan: $lastScan restarts=$totalRestarts"
                )
            )
            RuntimeLogger.log("Watchdog heartbeat", "HEALTH")
            heartbeatHandler.postDelayed(this, 10000)
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            var offline = 0; var degraded = 0; var restarted = 0

            AdapterHealthRegistry.getAll().forEach { snapshot ->
                val status = AdapterHealthRegistry.effectiveStatus(snapshot.adapterName)
                ProcessSurvivalRegistry.update(snapshot.adapterName, status)

                when (status) {
                    "OFFLINE" -> {
                        offline++
                        RuntimeLogger.log("WATCHDOG OFFLINE: ${snapshot.adapterName}", "WATCHDOG")
                        // PHASE3: attempt restart — this is the actual guardian action
                        restartOffline(snapshot.adapterName)
                        restarted++
                        totalRestarts++
                    }
                    "DEGRADED" -> {
                        degraded++
                        RuntimeLogger.log("WATCHDOG DEGRADED: ${snapshot.adapterName}", "WATCHDOG")
                    }
                }
            }

            lastScan = "offline=$offline degraded=$degraded restarted=$restarted"
            watchdogHandler.postDelayed(this, 15000)
            // HYPEROS LMK SURVIVAL: Renew WakeLock timeout on every successful scan cycle
            wakeLock?.acquire(10 * 60 * 1000L)
        }
    }

    private val watchdogHandler = Handler(Looper.getMainLooper())

    private fun restartOffline(adapterName: String) {
        val className = adapterMap[adapterName] ?: return
        try {
            val cls = Class.forName(className)
            val intent = Intent(this, cls)
            startService(intent)
            RuntimeLogger.log("WATCHDOG RESTART: $adapterName -> $className", "WATCHDOG")
        } catch (e: Exception) {
            RuntimeLogger.log("WATCHDOG RESTART FAILED: $adapterName — ${e.message}", "WATCHDOG")
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("WatchdogAdapterService started - ACTIVE GUARDIAN", "ADAPTER")
        NodeNotificationHub.attach(this, "adapter_watchdog")
        // HYPEROS LMK SURVIVAL: Acquire PARTIAL_WAKE_LOCK to prevent CPU dozing during 15s watchdog scans
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Splendor:WatchdogWakeLock").apply {
            acquire(10 * 60 * 1000L) // 10 minutes, renewable on each scan cycle
        }
        AdapterHealthRegistry.update(
            AdapterHealthSnapshot(
                adapterName = "adapter_watchdog",
                status = "ACTIVE",
                lastHeartbeat = System.currentTimeMillis(),
                errorCount = 0,
                recoveryCount = 0,
                details = "Active guardian with restart capability"
            )
        )
        heartbeatHandler.post(heartbeatRunnable)
        RuntimeLogger.log("Watchdog heartbeat scheduler started", "HEALTH")
        watchdogHandler.postDelayed(watchdogRunnable, 20000L) // first scan after 20s
        RuntimeLogger.log("Watchdog scanner started - ACTIVE GUARDIAN MODE", "WATCHDOG")
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        watchdogHandler.removeCallbacksAndMessages(null)
        NodeNotificationHub.detach(this, "adapter_watchdog")
        // HYPEROS LMK SURVIVAL: Release WakeLock to prevent battery drain on service shutdown
        wakeLock?.let { if (it.isHeld) it.release() }
        RuntimeLogger.log("Watchdog stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
