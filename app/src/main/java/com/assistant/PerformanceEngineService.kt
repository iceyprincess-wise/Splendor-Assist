package com.assistant

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.assistant.diagnostic.MachineLedger
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

class PerformanceEngineService : Service() {

    // No68-E-D31: the heartbeat hard-coded errorCount = 0 and
    // recoveryCount = 0, so the watchdog read ACTIVE/0-errors even on a
    // boot where half the engines threw in onCreate. Count real
    // ignition failures and report them.
    @Volatile private var engineStartFailures = 0

    // No68-E-D31b: the 20 try/catch ignition blocks were copy-pasted;
    // the helper keeps the exact log text and order, counts a failure,
    // and makes a missed stop/start visible in one place.
    private fun ignite(block: () -> Unit, logPrefix: String = "TRUTH SERUM EXCEPTION") {
        try {
            block()
        } catch (e: Throwable) {
            engineStartFailures++
            RuntimeLogger.log("$logPrefix: " + e.message, "ENGINE_FAULT")
        }
    }

    // Heartbeat for the watchdog: this service never reported health, so
    // the watchdog saw a missing snapshot as OFFLINE and restarted it
    // every 15s forever. Report ACTIVE every 10s (same cadence as the
    // watchdog's own heartbeat); a real crash leaves a stale snapshot,
    // which effectiveStatus() then reports OFFLINE.
    private val healthHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val healthHeartbeat = object : Runnable {
        override fun run() {
            try {
                com.assistant.diagnostic.registry.AdapterHealthRegistry.update(
                    com.assistant.diagnostic.registry.AdapterHealthSnapshot(
                        adapterName = "performance_engine",
                        status = "ACTIVE",
                        lastHeartbeat = System.currentTimeMillis(),
                        errorCount = engineStartFailures,
                        recoveryCount = 0,
                        details = if (engineStartFailures == 0) "service alive" else "service alive, $engineStartFailures engine ignition failure(s)"
                    )
                )
            } catch (_: Throwable) {}
            healthHandler.postDelayed(this, 10000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        RuntimeLogger.log("PerformanceEngineService started (Consolidated domain)", "ENGINE")

        NodeNotificationHub.attach(this, "performance_engine")
        com.assistant.diagnostic.registry.AdapterHealthRegistry.initialize(this)
        healthHandler.post(healthHeartbeat)

        // No68-E-D30 (O37-1, CLOSES the open queue item): MachineLedger
        // had a start/stop/snapshot surface and zero callers — its samples
        // were never collected. This service owns the monitoring loop, so
        // it starts the ledger here and stops it in onDestroy; the
        // snapshot reader is machineLedgerSnapshot() below.
        ignite({ MachineLedger.startMonitoring() })

        // IGNITE CONSOLIDATED PERFORMANCE ENGINES
        ignite({ PerformanceTelemetryRegistry.initialize(this) })
        ignite({ DisplayProfileEngine.detect(this) })
        ignite({ FramePacingEngine.start() })
        ignite({ MainThreadStallEngine.start() })
        ignite({ LagVerdictEngine.start() })
        ignite({ LoadShedGovernor.start() })
        ignite({ LoadShedCaptureBrakeEngine.start() })
        ignite({ ThermalPeekEngine.init(this) })
        ignite({ CpuGovernorEngine.start(this) }, "CPU OBSERVER EXCEPTION")
        ignite({ GcStallEngine.start() })
        ignite({ RenderThreadStallEngine.start() })
        ignite({ NetJitterEngine.start() })
        ignite({ NetProbeEngine.start(this) })
        ignite({ NetworkStateEngine.start(this) }) // SPLENDOR_P12_B13_CARRIER: NetworkStateEngine.start() had zero callers, so carrier stayed GENERIC and transport NONE while rtt/jitter were real TCP medians.
        ignite({ InputLatencyEngine.start() })
        // No68-I-D28: TouchQualityEngine.start() had zero callers anywhere,
        // so the touch-IRQ stall monitor never ran. Ignite it here with the
        // other input engines (same try/catch pattern as its neighbours).
        ignite({ TouchQualityEngine.start() })
        ignite({ StutterPulseEngine.start() })
        ignite({ ActionWindowEngine.start() })
        ignite({ BurstForensicsEngine.startDecay() })
        ignite({ MemoryMonitorEngine.start(this) })

        // Start consolidated scheduler for adapter telemetry
        ignite({ PerformanceScheduler.start(this) })

        // No68-E-D32: this logged "15 engines + scheduler" while 19
        // engines were actually ignited above (count them). The count
        // now matches the ignition list.
        // MERGE (no68): 19 -> 20 — famI No68-I-D28 adds TouchQualityEngine
        // to the ignition list above.
        RuntimeLogger.log("Performance engine stack ignited: 20 engines + scheduler", "ENGINE")
    }

    /** O37-1 reader: the MachineLedger samples collected since onCreate. */
    fun machineLedgerSnapshot(): List<MachineLedger.MachineSample> = MachineLedger.snapshot()

    fun machineLedgerSampleCount(): Int = MachineLedger.sampleCount()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // O37-2 (batch 5): cleanup first, the super call last — the
        // old order called super first, so detach() ran after the service
        // was already destroyed (WatchdogAdapterService already had the
        // correct order; this unifies the three).
        healthHandler.removeCallbacksAndMessages(null)
        // No68-E-D30b (O37-1): stop the ledger this service started.
        try { MachineLedger.stopMonitoring() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { PerformanceScheduler.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { NetProbeEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { NetworkStateEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { InputLatencyEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { TouchQualityEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { StutterPulseEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { ActionWindowEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { BurstForensicsEngine.stopDecay() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { MemoryMonitorEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { FramePacingEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { MainThreadStallEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { LagVerdictEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { LoadShedGovernor.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { LoadShedCaptureBrakeEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { CpuGovernorEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("CPU OBSERVER EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { GcStallEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { RenderThreadStallEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { NetJitterEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        // No68-E-D33: ThermalPeekEngine HAS a stop() (its thread polls
        // until running=false) but onDestroy never called it — the only
        // started engine with a stop that was never stopped. Its thread
        // survived service destruction and kept polling.
        try { ThermalPeekEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }

        NodeNotificationHub.detach(this, "performance_engine")
        RuntimeLogger.log("PerformanceEngineService destroyed", "ENGINE")
        super.onDestroy()
    }
}
