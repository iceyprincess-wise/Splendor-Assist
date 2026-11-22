package com.assistant



































import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger



import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry


























object LagVerdictEngine {

    // OMEGA FIX: Zero-Delay Micro-Defect Elimination
    // 2000ms poll rate is 4 seconds to detect CHOKING. Too slow for eFootball.
    private val POLL_MS: Long get() = 500L
    private val JITTER_MS: Float get() = 8f
    private val STABILITY_PCT: Float get() = 75f
    private val CHOKE_STALLS: Float get() = 12f
    private val CHOKE_MTSTALL_MS: Float get() = 80f
    private val CHOKE_SPIKES: Float get() = 15f
    private val CONFIRM_POLLS: Int get() = 1 // Instant confirmation for severe spikes

    // U2 (item 26, g54a): the four dead stall signals, read live here.
    // All four publishers run in :survival (PerformanceEngineService
    // ignites them), same process as this engine, so same-process reads
    // are valid — no IPC, no new channel. VSYNC_STARVATION /
    // THERMAL_THROTTLE_ACTIVE / LAGGING / TOUCH_IRQ_STALL all ride the
    // one bus inputClassification field (last writer wins); any of
    // them means the input path is starved. RenderThreadStall's
    // gpuQueueStallsPerMin is graded against the existing CHOKE_STALLS
    // (same unit, stalls/min — no new threshold). GcStall's
    // memoryPressure is the :survival heap, not the device: HIGH is
    // JITTERY (GC stall risk), CRITICAL is CHOKING.
    internal val starvedInputClassifications = setOf(
        "VSYNC_STARVATION", "THERMAL_THROTTLE_ACTIVE", "LAGGING", "TOUCH_IRQ_STALL"
    )

    // Rule B (item 26 amendment, Ed 2026-11-22): ThermalPeek was PROVEN
    // log-only — its only readers were two log strings in this file.
    // Its status string is now a live verdict input: SEVERE and above
    // (the same cut as AdapterSignalBus.thermalIsSevere, status >= 3)
    // is CHOKING. MODERATE is not mapped here — it already reaches
    // CHOKING through InputThermalEliminator's THERMAL_THROTTLE_ACTIVE.
    internal val severeThermalPeek = setOf("SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")

    internal fun classify(
        jit: Float, stab: Float, stallRate: Float, mtStall: Float, spm: Float,
        inputClass: String, gpuStalls: Float, memPressure: String, thermalPeek: String
    ): String = when {
        stallRate > CHOKE_STALLS || mtStall > CHOKE_MTSTALL_MS ||
            spm > CHOKE_SPIKES || inputClass in starvedInputClassifications ||
            gpuStalls > CHOKE_STALLS || memPressure == "CRITICAL" ||
            thermalPeek in severeThermalPeek -> "CHOKING"
        jit > JITTER_MS || stab < STABILITY_PCT || memPressure == "HIGH" -> "JITTERY"
        else -> "PENDING_SMOOTH"
    }

    @Volatile private var running = false
    @Volatile var verdict = "UNKNOWN"; private set
    @Volatile private var candidate = "UNKNOWN"
    @Volatile private var streak = 0
    @Volatile private var lastHeartbeat = 0L

    // SPLENDOR_P12_T4_LAGVERDICT: Choreographer keeps firing with capture
    // dead, so "DEVICE SMOOTH" was printed for a dead engine. The verdict
    // is only meaningful while capture is delivering frames.
    // F37-D7: FrameAssembler lives in the MAIN process, but this engine
    // runs in :survival, so the old read was always null and SMOOTH was
    // structurally unreachable. Read the cross-process heartbeat file
    // published by OverlayService after every assembled frame instead.
    // The 3s freshness keeps the P12_T4 guard: a dead capture still reads
    // UNKNOWN, never a false SMOOTH.
    private fun captureAlive(): Boolean = try {
        PerformanceTelemetryRegistry.currentCaptureAlive()
    } catch (_: Throwable) { false }

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val jit = FramePacingEngine.jitterMs
                    val stab = FramePacingEngine.stabilityPct
                    val stallRate = FramePacingEngine.stallsPerMin
                    val mtStall = MainThreadStallEngine.avgLatenessMs
                    val spm = MainThreadStallEngine.spikesPerMin
                    // U2 (item 26, g54a): live reads of the four dead
                    // stall signals (same :survival process — see
                    // classify() KDoc). Each read is guarded: a
                    // throwing reader must never kill the verdict loop.
                    val inputClass = try { AdapterSignalBus.inputClassification } catch (_: Throwable) { "UNKNOWN" }
                    val gpuStalls = try { RenderThreadStallEngine.gpuQueueStallsPerMin } catch (_: Throwable) { 0f }
                    val memPressure = try { GcStallEngine.memoryPressure } catch (_: Throwable) { "LOW" }
                    // Rule B (item 26 amendment): ThermalPeek status is a
                    // live verdict input now, not a log string.
                    val thermalPeek = try { ThermalPeekEngine.status } catch (_: Throwable) { "?" }
                    val decision = classify(jit, stab, stallRate, mtStall, spm, inputClass, gpuStalls, memPressure, thermalPeek)
                    val raw = when (decision) {
                        "CHOKING", "JITTERY" -> decision
                        else -> if (FramePacingEngine.avgGapMs > 0f && captureAlive()) "SMOOTH" else "UNKNOWN"
                    }
                    if (raw == candidate) streak++ else { candidate = raw; streak = 1 }
                    // Crowded zone (penalty box / corner) temporarily
                    // inflates stall/jitter from rendering load, not
                    // sustained device stress. Require 2 extra polls
                    // before confirming CHOKING to protect attacking actions.
                    // No68-C-D6: the crowded flag used to be read only from
                    // the in-process bus, which in :survival is never
                    // published (CrowdingZoneDetector runs in the main
                    // process), so this protection NEVER fired here. Read
                    // the cross-process registry first; the bus is kept as
                    // a same-process fallback.
                    val crowded = try {
                        com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.currentCrowdingZone()
                    } catch (_: Throwable) { AdapterSignalBus.crowdingZone }
                    val basePoll = if (CONFIRM_POLLS < 1) 1 else CONFIRM_POLLS
                    val need = if (raw == "CHOKING" && crowded)
                        basePoll + 2 else basePoll
                    val now = System.currentTimeMillis()
                    if (candidate != verdict && streak >= need) {
                        RuntimeLogger.log("DEVICE " + verdict + " -> " + candidate +
                            " (jitter=" + String.format("%.1f", jit) +
                            "ms stability=" + String.format("%.0f", stab) +
                            "% stalls/min=" + String.format("%.1f", stallRate) +
                            " mtStall=" + String.format("%.0f", mtStall) +
                            "ms input=" + inputClass +
                            " gpuStalls/min=" + String.format("%.1f", gpuStalls) +
                            " gcMem=" + memPressure +
                            " therm=" + ThermalPeekEngine.status + ")", "LAGVERDICT")
                        verdict = candidate
                        lastHeartbeat = now
                        // U1 (item 26, g54a): CHOKING takes the fast
                        // SEIZURE path — the capture-brake latch, not
                        // the 8 s governor arm. Any other verdict
                        // releases this engine's latch slot (the
                        // stutter slot is independent).
                        try { LoadShedCaptureBrakeEngine.requestFastBrakeLag(if (candidate == "CHOKING") 2 else 0) } catch (_: Throwable) {}
                        
                        // MASSIVE POWER: Performance Bee Intervention
                        // SPLENDOR_P7_AGGRO_READ_LAG_BEGIN
                        // Task 2C: read the cross-process registry value, not
                        // the bus. FilterEngine runs in the main process, so
                        // the bus copy here was always 1.0f and the "despite
                        // Filter Extremist Push" branch below could never fire.
                        val aggro = com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.currentFilterAggression()
                        // SPLENDOR_P7_AGGRO_READ_LAG_END
                        if (candidate == "CHOKING") {
                            try { com.assistant.diagnostic.AdapterSignalBus.publishExecutionBrake(2) } catch (_: Throwable) {}
                            // SPLENDOR_P7_DEVICE_BRAKE_LAG_BEGIN
                            // Mirror into the cross-process device snapshot so
                            // main-process FilterEngine reports the real brake.
                            try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceBrake(2) } catch (_: Throwable) {}
                            // SPLENDOR_P7_DEVICE_BRAKE_LAG_END
                            if (aggro > 1.5f) {
                                RuntimeLogger.log("LAG CHOKING: Execution brake applied despite Filter Extremist Push (${"%.2f".format(aggro)}x)", "LAG_BEE")
                            } else {
                                RuntimeLogger.log("LAG CHOKING: Execution brake applied to protect SmartAssist", "LAG_BEE")
                            }
                        } else if (candidate == "SMOOTH") {
                            try { com.assistant.diagnostic.AdapterSignalBus.publishExecutionBrake(0) } catch (_: Throwable) {}
                            // SPLENDOR_P7_DEVICE_BRAKE_LAG_SMOOTH
                            try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceBrake(0) } catch (_: Throwable) {}
                        }
                    } else if (now - lastHeartbeat >= 60_000L) {
                        lastHeartbeat = now
                        RuntimeLogger.log("DEVICE " + verdict +
                            " (jitter=" + String.format("%.1f", jit) +
                            "ms stability=" + String.format("%.0f", stab) +
                            "% therm=" + ThermalPeekEngine.status + ")", "LAGVERDICT")
                    }
                    AdapterSignalBus.publishLag(verdict)
                    PerformanceTelemetryRegistry.publishDisplay(
                        FramePacingEngine.avgGapMs, stallRate, mtStall, verdict)
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-verdict"; t.start()
    }

    fun stop() { running = false }
}

// --- MERGED: LoadShedCaptureBrakeEngine.kt ---

/**
 * LoadShedCaptureBrakeEngine — load shed → execution feedback bridge.
 *
 * No68-C-D37 (stale duplicate corrected — the canonical KDoc lives here
 * as this file's trailing merged doc; No68-C-D11 fixed the same class of
 * false claim in LoadShedCaptureBrakeEngine.kt itself, but this copy was
 * missed): the old "PROVEN GAP" paragraph claimed no component with
 * execution authority reads the load shed level. That is no longer true
 * for the capture gate — since No68-C-D4, OverlayService's
 * captureFrameIntervalMs reads recommendedIntervalMs() (max with the
 * memory-gate interval, floored at 30 fps in-game). The gesture
 * submission path does NOT read it either: it was wired to the brake
 * once (see No68-C-D5), and queue item 6 removed that gate — MOVE is
 * continuous steering, and shedding it removes gameplay, not cost.
 *
 * This engine translates load shed level into an execution throttle
 * signal published to AdapterSignalBus, consumed by OverlayService's
 * capture gate. The brake's lever is the capture rate
 * (recommendedIntervalMs), which stays; the gesture path is not a
 * consumer of the brake.
 *
 *   NONE  → executionBrake = 0  (normal operation)
 *   LIGHT → executionBrake = 1  (reduce capture rate: 15 fps out of game,
 *           floored to 30 fps in-game; keep gestures)
 *   HEAVY → executionBrake = 2  (10 fps out of game, floored to 30 fps
 *           in-game; gestures unaffected)
 *
 * The brake is polled every 500ms (faster than LoadShedGovernor's 2s poll)
 * so it responds within one poll cycle after the governor decides.
 */
