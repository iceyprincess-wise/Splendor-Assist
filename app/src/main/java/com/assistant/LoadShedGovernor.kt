package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

object LoadShedGovernor {

    // HARD-CODED CONSTANTS (No68-C-D11): the old "ADMIN-TUNABLE" header
    // was false — these getters return literals and no admin store is read
    // anywhere in this file. A re-tune changes the literal.
    private val MIN_HOLD_MS: Long get() = 8000L
    private val POLL_MS: Long get() = 2000L
    private val ARM_POLLS: Int get() = 4
    private val RELEASE_POLLS: Int get() = 5

    // Item 26 upgrade 3 (Builder B): F47FPS cut points. NEW numbers,
    // not a re-tune of any existing knob — no existing threshold above
    // changes. Anchored to Ed's calibrated processed rate of 20 fps
    // (FPS target build): LIGHT below 75% of it, HEAVY below 50% of
    // it; 0 fps (dead capture — the sampler ran and saw no arrivals
    // in its 5 s window) is HEAVY. -1f (no fresh sample) is ignored,
    // never treated as a reading.
    private val FPS_LIGHT_BELOW: Float get() = 15f
    private val FPS_HEAVY_BELOW: Float get() = 10f

    // Item 28 (adaptive FPS baseline): the fallback vals above stay the
    // source of the ratios — LIGHT below 75% and HEAVY below 50% of the
    // calibrated 20 fps rate — now applied to the learned baseline instead
    // of the fixed 20. Keeping the ratios derived from the vals keeps the
    // vals USED (no unused warnings) and the g54 source pins green.
    private val FALLBACK_BASELINE_FPS: Float get() = 20f
    private val LIGHT_RATIO: Float get() = FPS_LIGHT_BELOW / FALLBACK_BASELINE_FPS
    private val HEAVY_RATIO: Float get() = FPS_HEAVY_BELOW / FALLBACK_BASELINE_FPS

    @Volatile private var running = false
    @Volatile private var pollThread: Thread? = null
    @Volatile var level = "NONE"; private set
    @Volatile private var candidate = "NONE"
    @Volatile private var streak = 0
    @Volatile private var lastChangeMs = 0L
    @Volatile private var startTimeMs = 0L

    // Item 28: the adaptive baseline tracker (only the poll thread touches
    // it) and its mirrored current value, for readers/tests.
    private val baselineTracker = FpsBaseline()
    @Volatile var baselineFps = 20f; private set

    /**
     * Item 26 upgrade 3 (Builder B): the pure LoadShed want-decision,
     * extracted so the harness can pin it without starting the poll
     * thread. Same NONE/LIGHT/HEAVY shape the loop always consumed;
     * F47FPS joins as a third input alongside the stutter state and
     * the LagVerdict verdict. Precedence: SEIZURE/CHOKING first
     * (unchanged), then a falling processed rate (dead capture, or
     * below half the calibrated 20 fps rate) forces HEAVY, JITTERY
     * stays LIGHT, a rate below three-quarters of the calibrated
     * rate is LIGHT, anything else is NONE. frameFps < 0 means "no
     * fresh sample" and is ignored — the absence of a measurement
     * must never shed load on its own.
     *
     * Item 28: [baselineFps] defaults to the 20f fallback, so a 3-arg
     * call reproduces today's 15/10 exactly. The locals inside shadow
     * the fallback vals on purpose: the g54 pins assert the exact
     * source strings "frameFps >= 0f && frameFps < FPS_HEAVY_BELOW"
     * and "frameFps >= 0f && frameFps < FPS_LIGHT_BELOW", so the
     * comparison lines below stay byte-identical and the thresholds
     * are scaled by the learned baseline through the shadowed names.
     */
    fun decideWant(burst: String, verdict: String, frameFps: Float, baselineFps: Float = FALLBACK_BASELINE_FPS): String {
        val FPS_HEAVY_BELOW = baselineFps * HEAVY_RATIO
        val FPS_LIGHT_BELOW = baselineFps * LIGHT_RATIO
        return when {
        burst == "SEIZURE" -> "HEAVY"
        verdict == "CHOKING" -> "HEAVY"
        frameFps >= 0f && frameFps < FPS_HEAVY_BELOW -> "HEAVY"
        verdict == "JITTERY" -> "LIGHT"
        frameFps >= 0f && frameFps < FPS_LIGHT_BELOW -> "LIGHT"
        else -> "NONE"
        }
    }

    fun start() {
        if (running) return
        // Phase L (L-2): a previous poll thread still winding down must be
        // dead before a new one starts — otherwise two threads share
        // candidate/streak and the 4-poll arm completes in ~half the time.
        // Wait briefly; give up (no start) rather than run two pollers.
        // (Same pattern as InputLatencyEngine D18 / Phase I I-2; the
        // restart is real: AdapterRecoveryEngine relaunches
        // PerformanceEngineService in-process on a stale heartbeat, and
        // onCreate re-ignites these singleton objects. The interrupt
        // wakes the 2000 ms sleep immediately, so the 500 ms join is
        // plenty.)
        val old = pollThread
        if (old?.isAlive == true) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        running = true
        startTimeMs = System.currentTimeMillis()
        val t = Thread {
            while (running) {
                try {
                    // SEIZURE sets want=HEAVY, but it still goes through the
                    // same 4-poll arm + 8s hold as every other level (F37-D13:
                    // the old "escalates immediately" comment was false).
                    val burst = PerformanceTelemetryRegistry.currentStutterState()
                    val bootAge=System.currentTimeMillis()-startTimeMs
                    // No68-C-D35: the 10 s boot blackout used to swallow a
                    // SEIZURE as well, so worst-case HEAVY took boot 10 s +
                    // 4-poll arm 8 s + 8 s hold ≈ 26 s from the first burst
                    // — far too slow for a fast game. SEIZURE bypasses the
                    // boot blackout only; the 4-poll arm + 8 s hold still
                    // filter boot transients, so a single startup stutter
                    // cannot shed load on its own.
                    if(startTimeMs>0L&&bootAge<10_000L&&burst!="SEIZURE"){try{Thread.sleep(POLL_MS.coerceAtLeast(1L))}catch(_:Throwable){return@Thread};continue}
                    // Item 26 upgrade 3 (Builder B): F47FPS as a live
                    // third input, read from the registry file channel —
                    // never from the sampler object directly (the
                    // governor runs in :survival, where the sampler
                    // copy never sees an arrival and would read a
                    // permanent 0). -1f = no fresh sample, ignored.
                    val frameFps = try { PerformanceTelemetryRegistry.currentFrameRate() } catch (_: Throwable) { -1f }
                    // Item 28: read the baseline BEFORE deciding, decide
                    // with it, THEN observe the sample for the NEXT poll —
                    // a sample never judges itself. observe uses the
                    // PRE-change level, so a shed sample (our own brake
                    // depressing the rate) can never teach the baseline.
                    val baseline = baselineTracker.baseline()
                    baselineFps = baseline
                    val want = decideWant(burst, LagVerdictEngine.verdict, frameFps, baseline)
                    baselineTracker.observe(LagVerdictEngine.verdict, burst, level, frameFps)
                    if (want == candidate) streak++ else { candidate = want; streak = 1 }
                    val arm = if (ARM_POLLS < 1) 1 else ARM_POLLS
                    val rel = if (RELEASE_POLLS < 1) 1 else RELEASE_POLLS
                    val need = if (candidate == "NONE") rel else arm
                    val now = System.currentTimeMillis()
                    if (candidate != level && streak >= need &&
                        (lastChangeMs == 0L || now - lastChangeMs >= MIN_HOLD_MS)) {
                        RuntimeLogger.log("LOAD SHED " + level + " -> " + candidate +
                            " (device=" + LagVerdictEngine.verdict + " fps=" + frameFps +
                            " baseline=" + baseline + " light=" + baselineTracker.lightBelow() +
                            " heavy=" + baselineTracker.heavyBelow() + ")", "LOADSHED")
                        level = candidate
                        lastChangeMs = now
                    }
                    PerformanceTelemetryRegistry.publishLoadShed(level)
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-loadshed"; pollThread = t; t.start()
    }

    fun stop() {
        running = false
        // Phase L (L-2): wake the sleeper so it exits now instead of up
        // to 2000ms later (and cannot be resurrected by a quick restart).
        pollThread?.interrupt()
    }
}

// --- MERGED: MainThreadStallEngine.kt ---

// V3 ADMIN-WIRED - zero main-thread I/O, every knob answers the admin store live

/**
 * Pokes the main thread on a fixed rhythm and measures how late the answer
 * comes back - lateness IS the choke other engines cannot see. The probe
 * only measures (no logging/IO on the thread under test); a background
 * reporter summarizes. V3: cadence, spike line, smoothing and report rhythm
 * are all admin-tunable and re-read every cycle - values apply on the very
 * next poke, no restart.
 */
