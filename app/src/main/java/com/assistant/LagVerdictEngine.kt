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
                    val raw = when {
                        stallRate > CHOKE_STALLS || mtStall > CHOKE_MTSTALL_MS ||
                            spm > CHOKE_SPIKES -> "CHOKING"
                        jit > JITTER_MS || stab < STABILITY_PCT -> "JITTERY"
                        FramePacingEngine.avgGapMs > 0f && captureAlive() -> "SMOOTH"
                        else -> "UNKNOWN"
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
                            "ms therm=" + ThermalPeekEngine.status + ")", "LAGVERDICT")
                        verdict = candidate
                        lastHeartbeat = now
                        
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
 * submission path still does NOT read it (suppressMoveGestures() has zero
 * callers — see No68-C-D5 / QC-1).
 *
 * This engine translates load shed level into an execution throttle
 * signal published to AdapterSignalBus, consumed by OverlayService's
 * capture gate; the gesture submission path is a stated consumer that
 * was never wired.
 *
 *   NONE  → executionBrake = 0  (normal operation)
 *   LIGHT → executionBrake = 1  (reduce capture rate: 15 fps out of game,
 *           floored to 30 fps in-game; keep gestures)
 *   HEAVY → executionBrake = 2  (10 fps out of game, floored to 30 fps
 *           in-game; suppress MOVE-class only gestures — unwired, QC-1)
 *
 * The brake is polled every 500ms (faster than LoadShedGovernor's 2s poll)
 * so it responds within one poll cycle after the governor decides.
 */
