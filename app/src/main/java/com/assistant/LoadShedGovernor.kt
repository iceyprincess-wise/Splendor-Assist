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

    @Volatile private var running = false
    @Volatile var level = "NONE"; private set
    @Volatile private var candidate = "NONE"
    @Volatile private var streak = 0
    @Volatile private var lastChangeMs = 0L
    @Volatile private var startTimeMs = 0L

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
     */
    fun decideWant(burst: String, verdict: String, frameFps: Float): String = when {
        burst == "SEIZURE" -> "HEAVY"
        verdict == "CHOKING" -> "HEAVY"
        frameFps >= 0f && frameFps < FPS_HEAVY_BELOW -> "HEAVY"
        verdict == "JITTERY" -> "LIGHT"
        frameFps >= 0f && frameFps < FPS_LIGHT_BELOW -> "LIGHT"
        else -> "NONE"
    }

    fun start() {
        if (running) return
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
                    val want = decideWant(burst, LagVerdictEngine.verdict, frameFps)
                    if (want == candidate) streak++ else { candidate = want; streak = 1 }
                    val arm = if (ARM_POLLS < 1) 1 else ARM_POLLS
                    val rel = if (RELEASE_POLLS < 1) 1 else RELEASE_POLLS
                    val need = if (candidate == "NONE") rel else arm
                    val now = System.currentTimeMillis()
                    if (candidate != level && streak >= need &&
                        (lastChangeMs == 0L || now - lastChangeMs >= MIN_HOLD_MS)) {
                        RuntimeLogger.log("LOAD SHED " + level + " -> " + candidate +
                            " (device=" + LagVerdictEngine.verdict + " fps=" + frameFps + ")", "LOADSHED")
                        level = candidate
                        lastChangeMs = now
                    }
                    PerformanceTelemetryRegistry.publishLoadShed(level)
                } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-loadshed"; t.start()
    }

    fun stop() { running = false }
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
