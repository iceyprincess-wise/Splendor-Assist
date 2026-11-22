package com.assistant

/**
 * Queue item 28 (adaptive FPS baseline): the rolling healthy-rate baseline
 * the LoadShedGovernor derives its LIGHT/HEAVY cut points from, so the
 * governor is correct on any phone with zero manual re-tuning.
 *
 * Pure Kotlin — no Android imports, no threads, no logging, no I/O.
 * Instance state (tests create fresh instances); single-threaded — only
 * the governor poll thread ever calls it.
 *
 * Mechanism (NOT session-max: a session max misfires both ways — a laggy
 * start learns the laggy rate as normal, and menu arrivals ~60 fps make
 * in-game ~15 fps read as a collapse):
 * - Only healthy samples teach: verdict SMOOTH, burst not SEIZURE, level
 *   NONE (our own brake depresses the rate while shedding, so a shed
 *   sample is our own doing, not the device's healthy rate), and a fresh
 *   sample (frameFps >= 0f).
 * - In-band healthy samples (0.75x..1.5x baseline) feed a rolling window
 *   of the last 60; the candidate is the 25th percentile of the window
 *   (the match rate is the LOWEST healthy regime; menu spikes are HIGH
 *   outliers) and the baseline slews toward it (+0.25 / -0.5 fps per poll).
 * - Slow-steady: SMOOTH samples below 0.75x baseline accumulate; after 150
 *   consecutive polls (5 min) the baseline steps down to their median,
 *   bounded to >= 0.5x the current baseline.
 * - Re-seed: healthy samples above 1.5x baseline accumulate; after 150
 *   consecutive polls, all within +/-10% of their median (a tight, steady
 *   regime — a whole match on a faster device, not menu flicker), the
 *   baseline re-seeds to that median and the healthy window clears.
 * - Cold start: fewer than 10 healthy samples -> the 20f fallback, so the
 *   thresholds are 15/10 — identical to the fixed cut points.
 */
class FpsBaseline {

    companion object {
        const val FALLBACK_BASELINE_FPS = 20f
        const val WINDOW_SAMPLES = 60
        const val MIN_SAMPLES_TO_LEARN = 10
        const val LIGHT_RATIO = 0.75f
        const val HEAVY_RATIO = 0.5f
        const val UP_SLEW_PER_POLL = 0.25f
        const val DOWN_SLEW_PER_POLL = 0.5f
        const val UPPER_BAND_MULT = 1.5f
        const val LOWER_BAND_MULT = 0.75f
        const val RESEED_POLLS = 150
        const val RESEED_BAND_PCT = 0.10f
        const val SLOW_STEADY_POLLS = 150
    }

    private var baselineValue: Float = FALLBACK_BASELINE_FPS
    private val window = ArrayDeque<Float>()
    private val slowSamples = ArrayList<Float>()
    private val reseedSamples = ArrayList<Float>()
    private var slowStreak = 0
    private var reseedStreak = 0

    /** Current baseline — the 20f fallback until enough healthy samples learn it. */
    fun baseline(): Float = baselineValue

    /** LIGHT cut point: 75% of the baseline. */
    fun lightBelow(): Float = baselineValue * LIGHT_RATIO

    /** HEAVY cut point: 50% of the baseline. */
    fun heavyBelow(): Float = baselineValue * HEAVY_RATIO

    /**
     * One governor poll sample. [level] is the governor's level BEFORE this
     * poll's decision (the caller observes AFTER deciding, so a sample never
     * judges itself).
     */
    fun observe(verdict: String, burst: String, level: String, frameFps: Float) {
        // (a) No fresh sample: ignore entirely.
        if (frameFps < 0f) return

        // (b) Not healthy: reset both streak counters, learn nothing.
        if (verdict != "SMOOTH" || burst == "SEIZURE" || level != "NONE") {
            slowStreak = 0
            reseedStreak = 0
            slowSamples.clear()
            reseedSamples.clear()
            return
        }

        // (c) Healthy but slow-steady: below the lower band. A sample of any
        // other kind breaks the run, so the re-seed streak resets here too.
        if (frameFps < LOWER_BAND_MULT * baselineValue) {
            reseedStreak = 0
            reseedSamples.clear()
            slowStreak++
            slowSamples.add(frameFps)
            if (slowStreak >= SLOW_STEADY_POLLS) {
                val sorted = slowSamples.sorted()
                val median = sorted[(sorted.size - 1) / 2]
                val floor = HEAVY_RATIO * baselineValue
                baselineValue = if (median < floor) floor else median
                slowStreak = 0
                reseedStreak = 0
                slowSamples.clear()
            }
            return
        }

        // (d) Healthy but above the upper band: re-seed candidate. A sample
        // of any other kind breaks the run, so the slow streak resets here too.
        if (frameFps > UPPER_BAND_MULT * baselineValue) {
            slowStreak = 0
            slowSamples.clear()
            reseedStreak++
            reseedSamples.add(frameFps)
            if (reseedStreak >= RESEED_POLLS) {
                val sorted = reseedSamples.sorted()
                val median = sorted[(sorted.size - 1) / 2]
                val band = RESEED_BAND_PCT * median
                val tight = reseedSamples.all { it >= median - band && it <= median + band }
                if (tight) {
                    baselineValue = median
                    window.clear()
                }
                slowStreak = 0
                reseedStreak = 0
                reseedSamples.clear()
            }
            return
        }

        // (e) In-band healthy sample: resets both streaks, feeds the rolling
        // window, and slews the baseline toward the 25th percentile.
        slowStreak = 0
        reseedStreak = 0
        slowSamples.clear()
        reseedSamples.clear()
        window.addLast(frameFps)
        while (window.size > WINDOW_SAMPLES) window.removeFirst()
        if (window.size >= MIN_SAMPLES_TO_LEARN) {
            val sorted = window.sorted()
            val candidate = sorted[(sorted.size - 1) * 25 / 100]
            val delta = (candidate - baselineValue).coerceIn(-DOWN_SLEW_PER_POLL, UP_SLEW_PER_POLL)
            baselineValue += delta
        }
    }
}
