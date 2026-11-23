package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import java.util.Locale

/**
 * Item 26 amendment A (Builder C, g54c) — FPS-STABILISATION engine.
 *
 * Item 25 (audit, TRACE §4d + §5 item 3) PROVED F47FPS is a real measurement
 * with exactly two readers — the dashboard and a log line — and no decision
 * engine read it; FPSMonitor measures nothing and FramePacingEngine paces
 * nothing. Builder B (upgrade 3) wired F47FPS into LoadShedGovernor
 * (decideWant: low measured rate -> HEAVY/LIGHT) — this engine is the
 * complementary sustained-rate stabiliser: while the MEASURED processed
 * rate stays below the band the panel can sustain, it asks the capture
 * brake to shed the overlay's own capture work so the processed rate
 * stops sliding and recovers, with hysteresis so it does not whipsaw.
 * It stabilises the OVERLAY's processed rate (F47FPS), never the game's
 * FPS — the app cannot measure or set the game's FPS — and never changes
 * which gesture, direction or target a gameplay engine picks.
 *
 * ED'S DOCTRINE (Sun 2026-11-22 ~00:32 WAT): when the phone lags, stutters
 * or dips, the performance engine holds the DEVICE so the gameplay engine
 * can continue at full power. This engine holds the device (overlay
 * capture rate, via the brake) and nothing else.
 *
 * ADAPTIVE, DEVICE-AGNOSTIC: every number comes from a runtime measurement.
 * Measured processed rate: PerformanceTelemetryRegistry.currentFrameRate()
 * (F47FPS, published by DiagnosticSamplers from ImageReader arrivals).
 * Measured panel rate: DisplayProfileEngine.panelHz (real display).
 * The bands are FRACTIONS of the measured panel rate — engage brake 2
 * below panel/4, brake 1 below panel/3, release only at/above panel/2 —
 * so each device gets its own thresholds from its own panel (a 60 Hz
 * panel derives 15/20/30; a 120 Hz panel derives 30/40/60). No fps
 * number and no panel rate for any one device is hardcoded here.
 * If a measurement is missing (panel <= 0, or rate < 0 = no fresh F47FPS
 * sample) the engine degrades to measure-only: requestedBrake stays 0,
 * state NO_SAMPLE.
 *
 * LIVE CONSUMER (traced — same brake channel as FrameDropPreventionEngine):
 * DiagnosticSamplers F47FPS -> registry currentFrameRate() +
 * DisplayProfileEngine.panelHz -> this engine (evaluate/applyMeasured)
 * -> requestedBrake -> LoadShedCaptureBrakeEngine.computeBrake()
 * -> executionBrake -> OverlayService.captureFrameIntervalMs
 * (recommendedIntervalMs(), floored at 30 fps in-game) +
 * AdapterSignalBus.publishExecutionBrake -> MainProcessBusMirror ->
 * SpeedCompensationContributor (existing brake channel only).
 * HUNK H-C1 (PENDING, A-owned file): computeBrake() today takes
 * maxOf(governor, fastBrakeLag, fastBrakeStutter) — it has no slot for
 * this engine yet, so until hunk H-C1 lands, requestedBrake is computed,
 * stored and logged but the brake engine does not read it. Exact hunk in
 * harness-batch-perf-upgrades/TRACE-C.md §6 / BUILDER_REPORT-C.md.
 * Distinct from FrameDropPreventionEngine: that engine reacts to a
 * measured GAP SPIKE (burst, ~1 s); this one reacts to a SUSTAINED low
 * measured RATE (2 s poll + hysteresis). Each owns its own brake slot
 * in hunk H-C1, so neither can clear the other's live brake.
 */
object FpsStabilisationEngine {

    // Poll rhythm only (not a device threshold): 2 s, matching the
    // governor cadence — a sustained rate, not a spike, is judged here.
    private const val POLL_MS = 2000L

    // Bands as fractions of the MEASURED panel rate. HEAVY below 1/4,
    // LIGHT below 1/3, release only at/above 1/2 (hysteresis: a rate
    // that only just clears the engage line does not release the brake).
    private const val HEAVY_DIVISOR = 4f
    private const val LIGHT_DIVISOR = 3f
    private const val RELEASE_DIVISOR = 2f

    @Volatile private var running = false
    @Volatile private var pollThread: Thread? = null
    @Volatile var requestedBrake = 0; private set
    @Volatile var state = "NO_SAMPLE"; private set
    @Volatile var lastMeasuredFps = -1f; private set
    @Volatile var lastPanelHz = 0f; private set
    @Volatile private var lastLoggedBrake = -1

    /**
     * Pure decision with hysteresis: brake level (0/1/2) from the measured
     * processed rate against fractions of the measured panel rate.
     * currentLevel is the level already held: while held, the brake is
     * released only once the rate recovers to panel/2, not at the engage
     * line. 0 on missing measurement (measure-only degrade).
     */
    fun evaluate(measuredFps: Float, panelHz: Float, currentLevel: Int): Int {
        if (panelHz <= 0f) return 0
        if (measuredFps < 0f) return 0
        if (measuredFps < panelHz / HEAVY_DIVISOR) return 2
        if (measuredFps < panelHz / LIGHT_DIVISOR) return 1
        if (currentLevel > 0 && measuredFps < panelHz / RELEASE_DIVISOR) return currentLevel
        return 0
    }

    /** Shared apply path for pollOnce() and tests: stores, names state, logs on change. */
    fun applyMeasured(measuredFps: Float, panelHz: Float) {
        lastMeasuredFps = measuredFps
        lastPanelHz = panelHz
        val level = evaluate(measuredFps, panelHz, requestedBrake)
        requestedBrake = level
        // L-F04 (item 26 lead, hunk H-C1 second half): push the request into
        // the brake engine's own slot, so computeBrake() reads it.
        try { LoadShedCaptureBrakeEngine.requestFastBrakeFpsStab(level) } catch (_: Throwable) { }
        state = when {
            panelHz <= 0f || measuredFps < 0f -> "NO_SAMPLE"
            level == 2 -> "HOLD"
            level == 1 -> "GUARD"
            else -> "CALM"
        }
        if (level != lastLoggedBrake) {
            lastLoggedBrake = level
            try {
                RuntimeLogger.log(
                    "FpsStabilisation: measuredFps=" + String.format(Locale.US, "%.1f", measuredFps) +
                        " panel=" + String.format(Locale.US, "%.0f", panelHz) +
                        "Hz -> requestedBrake=" + level + " state=" + state,
                    "FPSSTAB"
                )
            } catch (_: Throwable) { }
        }
    }

    /** One live pass: read the measured sources, apply. */
    fun pollOnce() {
        try {
            val fps = try { PerformanceTelemetryRegistry.currentFrameRate() } catch (_: Throwable) { -1f }
            applyMeasured(fps, DisplayProfileEngine.panelHz)
        } catch (_: Throwable) { }
    }

    fun start() {
        if (running) return; running = true
        // Task 5 (gate): D18 double-start fix, R-1/R-3 shape (GcStall T-14).
        // stop() used to be bare `running = false` with no stored thread
        // and no interrupt: a restart inside the poll sleep left the old
        // poller alive beside the new one (both looping forever, since the
        // old thread wakes to running == true again). Join the old thread
        // 500 ms and give up - with running left true the old poller keeps
        // the engine alive on its own, so start() is a safe no-op.
        val old = pollThread
        if (old != null && old.isAlive) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        val t = Thread {
            while (running) {
                try { pollOnce() } catch (_: Throwable) { }
                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "fps-stabilisation"
        t.start()
        pollThread = t
        try { RuntimeLogger.log("FpsStabilisationEngine started", "FPSSTAB") } catch (_: Throwable) { }
    }

    fun stop() {
        running = false
        pollThread?.interrupt()
        requestedBrake = 0
        state = "NO_SAMPLE"
    }
}
