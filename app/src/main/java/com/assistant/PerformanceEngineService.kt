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
        // it starts the ledger here and stops it in onDestroy. The live
        // reader is DashboardInjector's "Machine:" line (Phase M, M-9),
        // which reads MachineLedger.latest()/sampleCount() directly; the
        // two delegating wrappers that used to live below were deleted
        // in Phase P (P-4) — zero callers, the wire already exists.
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
        ignite({ GcStallEngine.start() })
        ignite({ RenderThreadStallEngine.start() })
        ignite({ NetJitterEngine.start() })
        ignite({ NetProbeEngine.start(this) })
        ignite({ NetworkStateEngine.start(this) }) // SPLENDOR_P12_B13_CARRIER: NetworkStateEngine.start() had zero callers, so carrier stayed GENERIC and transport NONE while rtt/jitter were real TCP medians.
        // Batch 6d: DnsWarmupEngine.start() had zero callers anywhere — the
        // DNS warm loop never ran (warmNow() from NetworkStateEngine only
        // notifies a lock on a thread that never started). Same defect class
        // as 440/442 below; ignite it here with the other network engines.
        ignite({ DnsWarmupEngine.start() })
        ignite({ InputLatencyEngine.start() })
        // No68-I-D28: TouchQualityEngine.start() had zero callers anywhere,
        // so the touch-IRQ stall monitor never ran. Ignite it here with the
        // other input engines (same try/catch pattern as its neighbours).
        ignite({ TouchQualityEngine.start() })
        ignite({ StutterPulseEngine.start() })
        ignite({ ActionWindowEngine.start() })
        // Batch 6d: ConnectionHealEngine.start() and CongestionSentinelEngine.start()
        // both had zero callers anywhere — the healer thread (HOLD polling +
        // network-lost callback) and the congestion sentinel thread never ran,
        // so `congested` stayed false forever and HOLD never healed. Same
        // defect class as SPLENDOR_P12_B13_CARRIER (NetworkStateEngine) and
        // No68-I-D28 (TouchQualityEngine) above: ignite them here with the
        // other network engines. Both starts are idempotent (running guard).
        ignite({ ConnectionHealEngine.start(this) })
        ignite({ CongestionSentinelEngine.start() })
        ignite({ BurstForensicsEngine.startDecay() })
        ignite({ MemoryMonitorEngine.start(this) })
        // Batch 6e: InputPriorityEngine.start(), InputThermalEliminatorEngine.start(),
        // InputVsyncEliminatorEngine.start() and OomAdaptiveThrottleEngine.start() all
        // had zero callers anywhere — the four input eliminators never ran, so the
        // main-thread priority was never re-applied and the vsync/thermal/OOM
        // signals were never published (their bus signals have no other
        // producer). Same defect class as 440/442/459 (6d) and P12_B13_CARRIER:
        // ignite them here. All four starts are idempotent (running guard).
        ignite({ InputPriorityEngine.start() })
        ignite({ InputThermalEliminatorEngine.start(this) })
        ignite({ InputVsyncEliminatorEngine.start() })
        ignite({ OomAdaptiveThrottleEngine.start() })
        // Batch 6e: PacketLossProbeEngine.start(), PanelWatchEngine.start() and
        // RadioKeepAliveEngine.start() also had zero callers anywhere — the
        // loss input ActionWindowEngine reads (lossPct) stayed at its 0.0
        // default, the real panel rate never reached StutterPulseEngine, and
        // the radio keepalive never fired. Same defect class; ignite here.
        ignite({ PacketLossProbeEngine.start() })
        ignite({ PanelWatchEngine.start(this) })
        ignite({ RadioKeepAliveEngine.start() })
        // Batch booster: the free booster engine (queue item 4). Its
        // controller polls the match signal itself and only acts while a
        // match is on; it never throttles a gameplay engine — it sheds
        // its own work when the link degrades. Same ignite pattern as its
        // neighbours; start is idempotent (running guard).
        ignite({ FreeBoosterController.start(this) })
        // Item 26 amendment A (Builder C, g54c): the two new engines.
        // FrameDropPreventionEngine answers a measured gap spike (~1 s);
        // FpsStabilisationEngine answers a sustained low measured rate
        // (2 s + hysteresis). Both shed only the overlay's own capture
        // work, via the brake channel (hunk H-C1 pending in
        // LoadShedCaptureBrakeEngine); neither touches a gameplay pick.
        // Both starts are idempotent (running guard).
        ignite({ FrameDropPreventionEngine.start() })
        ignite({ FpsStabilisationEngine.start() })

        // Start consolidated scheduler for adapter telemetry
        ignite({ PerformanceScheduler.start(this) })

        // No68-E-D32: this logged "15 engines + scheduler" while 19
        // engines were actually ignited above (count them). The count
        // now matches the ignition list.
        // MERGE (no68): 19 -> 20 — famI No68-I-D28 adds TouchQualityEngine
        // to the ignition list above.
        // Batch 6d: 20 -> 23 — DnsWarmupEngine, ConnectionHealEngine and
        // CongestionSentinelEngine were ignited without updating this count.
        // Batch 6e: 23 -> 30 — the seven zero-caller starters above
        // (InputPriority, InputThermal, InputVsync, OOM, PacketLoss,
        // PanelWatch, RadioKeepAlive) are ignited here.
        // Batch booster: 30 -> 31 — FreeBoosterController is ignited
        // above (queue item 4, free booster engine).
        // Item 26 amendment A (Builder C, g54c): 31 -> 33 —
        // FrameDropPreventionEngine + FpsStabilisationEngine are ignited
        // above. Recount (PROVEN by grep, not guessed): 34 ignite() calls
        // in this file = 1 MachineLedger + 1 registry + 29 pre-existing
        // engines + 2 new engines + 1 scheduler; the log counts the 33
        // non-scheduler calls as "engines", the same basis as the old 31
        // (MachineLedger and the registry counted as engines there too —
        // that is the 31-vs-32 discrepancy the audit noted: 32 calls,
        // 31 non-scheduler). No other site names the total (repo-wide
        // grep for "31 engines" hits only this line).
        RuntimeLogger.log("Performance engine stack ignited: 33 engines + scheduler", "ENGINE")
    }

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
        try { GcStallEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { RenderThreadStallEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { NetJitterEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        // No68-E-D33: ThermalPeekEngine HAS a stop() (its thread polls
        // until running=false) but onDestroy never called it — the only
        // started engine with a stop that was never stopped. Its thread
        // survived service destruction and kept polling.
        try { ThermalPeekEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        // Batch 6d trio (same class as No68-E-D33): DnsWarmupEngine,
        // ConnectionHealEngine and CongestionSentinelEngine all HAVE a
        // stop() but onDestroy never called it — their threads survived
        // service destruction and kept polling.
        try { DnsWarmupEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { ConnectionHealEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { CongestionSentinelEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        // Batch 6e: stop the seven newly-ignited engines (each HAS a
        // stop(); without these their threads survive destruction too).
        try { InputPriorityEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { InputThermalEliminatorEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { InputVsyncEliminatorEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { OomAdaptiveThrottleEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { PacketLossProbeEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { PanelWatchEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { RadioKeepAliveEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        // Batch booster: stop the controller this service started (it
        // releases the transport request, the wake lock, the signal
        // reader, the keepalive boost and the DNS pause).
        try { FreeBoosterController.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        // Item 26 amendment A (Builder C, g54c): stop the two new engines
        // this service started (each HAS a stop(); without these their
        // threads survive destruction too).
        try { FrameDropPreventionEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }
        try { FpsStabilisationEngine.stop() } catch (e: Throwable) { RuntimeLogger.log("TRUTH SERUM EXCEPTION: " + e.message, "ENGINE_FAULT") }

        NodeNotificationHub.detach(this, "performance_engine")
        RuntimeLogger.log("PerformanceEngineService destroyed", "ENGINE")
        super.onDestroy()
    }
}
