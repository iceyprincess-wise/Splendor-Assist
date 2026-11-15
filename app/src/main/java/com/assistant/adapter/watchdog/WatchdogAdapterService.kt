package com.assistant.adapter.watchdog

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.survival.AdapterStatusRegistry

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
    // SPLENDOR_P11_D5: key of the last watchdog death report
    // ("<pid>|<startedMs>") — one report per dead session, so the 15s scan
    // cannot spam the crash file.
    @Volatile private var lastWatchdogReportKey: String? = null
    @Volatile private var totalRestarts = 0
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    // Map from adapter registry name to its service class name
    private val adapterMap = mapOf(
        "performance_engine" to "com.assistant.PerformanceEngineService",
        "gameplay_engine"    to "com.assistant.GameplayEngineService"
    )

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AdapterHealthRegistry.update(
                AdapterHealthSnapshot(
                    adapterName = "adapter_watchdog",
                    status = if (lastScan == "no scan yet") "STARTING" else "ACTIVE",
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
            var offline = 0; var degraded = 0; var active = 0; var restarted = 0
            val liveSnapshots = AdapterHealthRegistry.getAllLive().associateBy { it.adapterName }
            val checked = mutableSetOf<String>()

            adapterMap.keys.forEach { name ->
                checked.add(name)
                val snapshot = liveSnapshots[name]
                // Missing snapshot means "never heard from", not "dead": a
                // crashed service leaves a stale snapshot, which
                // effectiveStatus() reports OFFLINE. Never restart on UNKNOWN.
                val status = if (snapshot == null) "UNKNOWN" else AdapterHealthRegistry.effectiveStatus(snapshot)
                AdapterStatusRegistry.update(name, status)

                when (status) {
                    "OFFLINE" -> {
                        offline++
                        RuntimeLogger.log("WATCHDOG OFFLINE: $name", "WATCHDOG")
                        restartOffline(name)
                        restarted++
                        totalRestarts++
                    }
                    "DEGRADED" -> {
                        degraded++
                        RuntimeLogger.log("WATCHDOG DEGRADED: $name", "WATCHDOG")
                    }
                    "ACTIVE" -> active++
                }
            }

            liveSnapshots.values.forEach { snapshot ->
                if (!checked.contains(snapshot.adapterName)) {
                    val status = AdapterHealthRegistry.effectiveStatus(snapshot)
                    AdapterStatusRegistry.update(snapshot.adapterName, status)
                    when (status) {
                        "OFFLINE" -> offline++
                        "DEGRADED" -> degraded++
                        "ACTIVE" -> active++
                    }
                }
            }
            AdapterSignalBus.publishFleet(offline)
            lastScan = "offline=$offline degraded=$degraded restarted=$restarted active=$active"
            // Fleet-wide counts from the merged live set. The old formula
            // maxOf(0, adapterMap.size(2) - offline - degraded) undercounted
            // active whenever the fleet held more than the 2 restartable
            // adapters (offline/degraded already counted fleet-wide).
            com.assistant.survival.AdapterHealthCounts.update(active, degraded, offline)
            // SPLENDOR_P11_D5: cross-process marker watchdog (see
            // checkMainProcessMarker below).
            checkMainProcessMarker()
            watchdogHandler.postDelayed(this, 15000)
            // HYPEROS LMK SURVIVAL: Renew WakeLock timeout on every successful scan cycle
            wakeLock?.acquire(10 * 60 * 1000L)
        }
    }

    private val watchdogHandler = Handler(Looper.getMainLooper())

    // SPLENDOR_P11_D5: cross-process watchdog. DeathWatch can only report a
    // death on the NEXT main-process start, so a death with no restart was
    // never reported. This service runs in :survival with a wake lock and
    // outlives the main process: if the heartbeat marker is stale > 60s
    // while the main process is not running, record one throttled watchdog
    // entry (one per pid/startedMs). Fail-closed: an unreadable process list
    // or unreadable storage reports nothing (a false death report would be
    // worse than a missed one).
    private fun checkMainProcessMarker() {
        try {
            if (!com.assistant.storage.SplendorStorageRoot.isReady()) return
            val dir = com.assistant.storage.SplendorStorageRoot.subdirectory("deathwatch")
            val markers = dir.listFiles { f -> f.isFile && f.name.endsWith(".marker") && !f.name.endsWith(".java-crash.marker") } ?: return
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
            val procs = am.runningAppProcesses ?: return
            if (procs.any { it.processName == packageName }) return
            val now = System.currentTimeMillis()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            for (marker in markers) {
                val raw = try { marker.readText() } catch (_: Throwable) { continue }
                val p = raw.split("|")
                if (p.size < 5) continue
                val lastBeat = p[4].toLongOrNull() ?: continue
                if (now - lastBeat <= 60_000L) continue
                val key = "${p.getOrNull(2) ?: "?"}|${p.getOrNull(3) ?: "?"}"
                if (key == lastWatchdogReportKey) continue
                lastWatchdogReportKey = key
                val began = p[3].toLongOrNull()?.let { fmt.format(java.util.Date(it)) } ?: p[3]
                val entry = "\n===== WATCHDOG: MAIN PROCESS UNRESPONSIVE =====\n" +
                    "Detected at   : " + fmt.format(java.util.Date(now)) + "\n" +
                    "Dead process  : " + (p.getOrNull(1) ?: "?") + "  (pid " + (p.getOrNull(2) ?: "?") + ")\n" +
                    "Session began : " + began + "\n" +
                    "Last heartbeat: " + fmt.format(java.util.Date(lastBeat)) + "\n" +
                    "Last beat age at detection: " + ((now - lastBeat) / 1000L) + "s (includes the delay before this watchdog report)\n" +
                    "VERDICT       : WATCHDOG — main process not in runningAppProcesses and marker stale > 60s; no restart observed to report the death\n" +
                    "Report source : WatchdogAdapterService.checkMainProcessMarker (:survival)\n" +
                    "==================================\n"
                try {
                    com.assistant.storage.SplendorStorageRoot.file("Splendor_Crash_Reports.txt").appendText(entry)
                } catch (t: Throwable) {
                    android.util.Log.e("WatchdogAdapterService", "watchdog death report write failed", t)
                }
                RuntimeLogger.log("WATCHDOG main process unresponsive since lastBeat=$lastBeat pid=${p.getOrNull(2)}", "WATCHDOG")
            }
        } catch (_: Throwable) { }
    }

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
        AdapterHealthRegistry.initialize(this)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder
}
