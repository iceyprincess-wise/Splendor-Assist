package com.assistant

import com.assistant.diagnostic.RuntimeLogger

/**
 * Item 26 amendment A (Builder C, g54c) — FRAME-DROP PREVENTION engine.
 *
 * Item 25 (audit, TRACE §4d) PROVED no engine prevents frame drops: everything
 * either measures/logs, or acts only on the overlay's own work. This engine
 * closes the prevention half for the overlay's own processed frames: it watches
 * the MEASURED frame gaps and, while a drop burst is live, asks the capture
 * brake to shed the overlay's own capture work so the next frames have room.
 * It never touches the game's FPS (the app cannot measure or set it — 0 hits
 * repo-wide for setFrameRate / preferredDisplayModeId) and never changes
 * which gesture, direction or target a gameplay engine picks.
 *
 * ED'S DOCTRINE (Sun 2026-11-22 ~00:32 WAT): when the phone lags, stutters
 * or dips, the performance engine holds the DEVICE so the gameplay engine
 * can continue at full power — "gameplay continue, be powerful, don't stop
 * — leave the lagging for me, I'll hold the device." This engine holds the
 * device (overlay capture rate, via the brake) and nothing else.
 *
 * ADAPTIVE, DEVICE-AGNOSTIC: every number comes from a runtime measurement.
 * Panel rate / vsync budget: DisplayProfileEngine.vsyncBudgetMs (measured
 * from the real display at detect()). Frame gaps: FramePacingEngine
 * (Choreographer gaps on this device, right now). The decision compares
 * measured gaps to MULTIPLES of the measured vsync budget — 4x (the same
 * burst line StutterPulseEngine already uses: gap > 4x vsync) and 2x/1x —
 * so a 60 Hz panel, a 90 Hz panel and a 120 Hz panel each get their own
 * thresholds from their own measurement. No fps number, no panel rate and
 * no millisecond threshold for any one device is hardcoded here.
 * If a measurement is missing (budget <= 0, or no frames seen yet) the
 * engine degrades to measure-only: requestedBrake stays 0, state NO_SAMPLE.
 *
 * LIVE CONSUMER (traced, CALLER -> ENGINE -> CALLEE -> CONSUMER):
 * FramePacingEngine (measured gaps) + DisplayProfileEngine (measured budget)
 * -> this engine (evaluate/applyMeasured) -> requestedBrake
 * -> LoadShedCaptureBrakeEngine.computeBrake() -> executionBrake
 * -> OverlayService.captureFrameIntervalMs (recommendedIntervalMs(), floored
 *    at 30 fps in-game) + AdapterSignalBus.publishExecutionBrake ->
 *    MainProcessBusMirror -> SpeedCompensationContributor (gesture-duration
 *    scaling, the existing brake channel only).
 * HUNK H-C1 (PENDING, A-owned file): computeBrake() today takes
 * maxOf(governor, fastBrakeLag, fastBrakeStutter) — it has no slot for this
 * engine yet, so until hunk H-C1 lands, requestedBrake is computed, stored
 * and logged but the brake engine does not read it. The exact hunk is in
 * harness-batch-perf-upgrades/TRACE-C.md §6 / BUILDER_REPORT-C.md. Do NOT
 * hijack requestFastBrakeLag/Stutter: those slots belong to LagVerdict /
 * BurstForensics and their owners overwrite them every poll.
 */
object FrameDropPreventionEngine {

    // Poll rhythm only (not a device threshold): 1 s, same order as the
    // brake engine's 500 ms poll so a burst is answered within ~1 s.
    private const val POLL_MS = 1000L

    // Burst line: a gap beyond 4x the MEASURED vsync budget is a dropped
    // frame (same 4x line StutterPulseEngine uses). Guard line: average
    // gap beyond 2x budget, or beat-to-beat jitter beyond 1x budget.
    // Sustained stalls: 12/min reuses the repo's existing CHOKE_STALLS
    // notion from LagVerdictEngine (12 stalls/min), not a new number.
    private const val BURST_MULT = 4f
    private const val GUARD_GAP_MULT = 2f
    private const val GUARD_JITTER_MULT = 1f
    private const val STALLS_PER_MIN_HEAVY = 12f

    @Volatile private var running = false
    @Volatile var requestedBrake = 0; private set
    @Volatile var state = "NO_SAMPLE"; private set
    @Volatile var lastWorstGapMs = 0f; private set
    @Volatile var lastAvgGapMs = 0f; private set
    @Volatile var lastJitterMs = 0f; private set
    @Volatile var lastStallsPerMin = 0f; private set
    @Volatile var lastVsyncBudgetMs = 0f; private set
    @Volatile private var lastLoggedBrake = -1

    /**
     * Pure decision: brake level (0/1/2) from measured gaps against the
     * measured vsync budget. 2 = drop burst live (worst gap beyond 4x
     * budget); 1 = guard (average gap beyond 2x budget, or jitter beyond
     * 1x budget); 0 = calm or no measurement. No device number inside.
     */
    fun evaluate(avgGapMs: Float, jitterMs: Float, worstGapMs: Float, stallsPerMin: Float, vsyncBudgetMs: Float): Int {
        if (vsyncBudgetMs <= 0f) return 0
        if (avgGapMs <= 0f && worstGapMs <= 0f) return 0
        if (worstGapMs > BURST_MULT * vsyncBudgetMs) return 2
        if (stallsPerMin >= STALLS_PER_MIN_HEAVY) return 2
        if (avgGapMs > GUARD_GAP_MULT * vsyncBudgetMs) return 1
        if (jitterMs > GUARD_JITTER_MULT * vsyncBudgetMs) return 1
        return 0
    }

    /** Shared apply path for pollOnce() and tests: stores, names state, logs on change. */
    fun applyMeasured(avgGapMs: Float, jitterMs: Float, worstGapMs: Float, stallsPerMin: Float, vsyncBudgetMs: Float) {
        lastAvgGapMs = avgGapMs
        lastJitterMs = jitterMs
        lastWorstGapMs = worstGapMs
        lastStallsPerMin = stallsPerMin
        lastVsyncBudgetMs = vsyncBudgetMs
        val level = evaluate(avgGapMs, jitterMs, worstGapMs, stallsPerMin, vsyncBudgetMs)
        requestedBrake = level
        // L-F04 (item 26 lead, hunk H-C1 second half): push the request into
        // the brake engine's own slot, so computeBrake() reads it.
        try { LoadShedCaptureBrakeEngine.requestFastBrakeFrameDrop(level) } catch (_: Throwable) { }
        state = when {
            vsyncBudgetMs <= 0f || (avgGapMs <= 0f && worstGapMs <= 0f) -> "NO_SAMPLE"
            level == 2 -> "HOLD"
            level == 1 -> "GUARD"
            else -> "CALM"
        }
        if (level != lastLoggedBrake) {
            lastLoggedBrake = level
            try {
                RuntimeLogger.log(
                    "FrameDropPrevention: worst=" + String.format("%.1f", worstGapMs) +
                        "ms avg=" + String.format("%.1f", avgGapMs) +
                        "ms jitter=" + String.format("%.1f", jitterMs) +
                        "ms budget=" + String.format("%.1f", vsyncBudgetMs) +
                        "ms stalls/min=" + String.format("%.1f", stallsPerMin) +
                        " -> requestedBrake=" + level + " state=" + state,
                    "FRAMEDROP"
                )
            } catch (_: Throwable) { }
        }
    }

    /** One live pass: read the measured sources, apply. */
    fun pollOnce() {
        try {
            applyMeasured(
                FramePacingEngine.avgGapMs,
                FramePacingEngine.jitterMs,
                FramePacingEngine.worstGapMs,
                FramePacingEngine.stallsPerMin,
                DisplayProfileEngine.vsyncBudgetMs
            )
        } catch (_: Throwable) { }
    }

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try { pollOnce() } catch (_: Throwable) { }
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "frame-drop-prevention"
        t.start()
        try { RuntimeLogger.log("FrameDropPreventionEngine started", "FRAMEDROP") } catch (_: Throwable) { }
    }

    fun stop() {
        running = false
        requestedBrake = 0
        state = "NO_SAMPLE"
    }
}
