package com.assistant

/**
 * Item 26 (Builder B) — REAL implementation, driven by NetProbe's measured
 * jitter. The Patch 4 stub was start()/stop() only: the polling loop was
 * deleted because its RTT value was never read and this engine computed
 * no jitter of its own — but NetProbeEngine already computes real jitter (EWMA,
 * ALPHA 0.35, of |median RTT - smoothed RTT| over 3 TCP-connect samples),
 * and ActionWindowEngine / CongestionSentinelEngine already read that
 * value directly. So this engine adds NO new thread, NO new socket, NO
 * new poll: NetProbeEngine.probeOnce() calls onSample() on its existing
 * 2–5 s cadence, and this engine turns the measured jitter into the one
 * thing nobody produced — a jitter-ONLY verdict (STABLE / JITTERY).
 * NetProbe's own quality (GOOD/DEGRADED/BAD) conflates RTT and jitter,
 * so a high-RTT / low-jitter link reads DEGRADED while its jitter is
 * fine; the verdict here answers only "is the link jittery", against
 * the detected carrier's own tolerance (CarrierProfileEngine.jitterTolMs:
 * 8 ms MTN / 10 ms Airtel / 12 ms GENERIC / 15 ms Wi-Fi) — no invented
 * threshold. Two consecutive samples flip the verdict either way, so a
 * single spike cannot flap it. Log lines fire only on a flip (proof of
 * life, per Ed's doctrine: the verdict is the product, the log is the
 * proof). Live state (jitterMs / verdict / sustainedJittery) is readable
 * by any :survival consumer; CongestionSentinelEngine reads
 * sustainedJittery (item 27 adoption). ActionWindowEngine's
 * hand-rolled jitter check stays deliberately unadopted (item 27 —
 * its 2x HOLD bar is a severity threshold, a different test).
 *
 * Doctrine (Ed, 2026-11-22): the performance engine holds the DEVICE so
 * gameplay continues at full power. This engine holds nothing back and
 * costs nothing live (see onSample: one volatile read + one comparison
 * per probe). It never touches a gesture, direction, or target.
 */
object NetJitterEngine {
    @Volatile private var running = false
    @Volatile var jitterMs = 0f; private set
    @Volatile var verdict = "UNKNOWN"; private set
    @Volatile private var aboveStreak = 0
    @Volatile private var belowStreak = 0

    // Stability count, not a tuned threshold: how many consecutive
    // samples on one side of tolerance flip the verdict.
    private const val FLIP_SAMPLES = 2

    /** Jitter-only test: strictly above the carrier tolerance is jittery. */
    fun isJittery(jitter: Float, tolMs: Float): Boolean = jitter > tolMs

    val sustainedJittery: Boolean get() = verdict == "JITTERY"

    fun start() {
        if (running) return
        running = true
        // Seed from the latest probe so the verdict is not UNKNOWN for a
        // whole probe interval after start (NetProbe may already be running).
        try { onSample(NetProbeEngine.jitter, CarrierProfileEngine.jitterTolMs.toFloat()) } catch (_: Throwable) { }
    }

    fun stop() { running = false }

    /**
     * One NetProbe sample, on NetProbe's own cadence. Pure apart from the
     * volatile verdict state; no I/O, no thread, no socket. Called from
     * NetProbeEngine.probeOnce() after publishNet/publishPing.
     */
    fun onSample(jitter: Float, tolMs: Float) {
        if (!running) return
        jitterMs = jitter
        if (isJittery(jitter, tolMs)) {
            aboveStreak++; belowStreak = 0
            if (aboveStreak >= FLIP_SAMPLES && verdict != "JITTERY") {
                val prev = verdict
                verdict = "JITTERY"
                try { com.assistant.diagnostic.RuntimeLogger.log(
                    "NETJITTER $prev -> JITTERY (jitter=" + jitter + "ms tol=" + tolMs + "ms)", "NET") } catch (_: Throwable) { }
            }
        } else {
            belowStreak++; aboveStreak = 0
            if (belowStreak >= FLIP_SAMPLES && verdict != "STABLE") {
                val prev = verdict
                verdict = "STABLE"
                try { com.assistant.diagnostic.RuntimeLogger.log(
                    "NETJITTER $prev -> STABLE (jitter=" + jitter + "ms tol=" + tolMs + "ms)", "NET") } catch (_: Throwable) { }
            }
        }
    }
}
