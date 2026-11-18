package com.assistant






















import android.os.Handler


import android.os.Looper







import android.view.Choreographer


import com.assistant.diagnostic.RuntimeLogger

























import kotlin.math.max




object FramePacingEngine {

    // HARD-CODED CONSTANTS (No68-C-D36): the old "ADMIN-TUNABLE" header
    // was false — these getters return literals and no admin store is read
    // anywhere in this file. A re-tune changes the literal.
    private val ALPHA: Float get() = 0.2f
    private val REPORT_EVERY_MS: Long get() = 20_000L
    private val STALL_MS: Float get() = 100f

    @Volatile private var running = false
    @Volatile var avgGapMs = 0f; private set
    @Volatile var jitterMs = 0f; private set
    @Volatile var stabilityPct = 100f; private set
    @Volatile var stallsPerMin = 0f; private set
    @Volatile var worstGapMs = 0f; private set
    @Volatile private var lastNanos = 0L
    @Volatile private var lastGap = 0f
    @Volatile private var frames = 0L
    @Volatile private var totalStalls = 0L

    // per-window vsync-multiple buckets: [1x, 2x, 3x, 4x+] + stalls
    private val bucket = LongArray(4)
    @Volatile private var winStalls = 0L
    @Volatile private var winFrames = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNanos > 0L) {
                val gap = (frameTimeNanos - lastNanos) / 1_000_000f
                frames++; winFrames++
                val a = ALPHA
                avgGapMs = if (avgGapMs == 0f) gap else avgGapMs * (1 - a) + gap * a
                if (lastGap > 0f) {
                    val d = Math.abs(gap - lastGap)
                    jitterMs = jitterMs * (1 - a) + d * a
                }
                lastGap = gap
                if (gap > worstGapMs) worstGapMs = gap
                if (gap > STALL_MS) { winStalls++; totalStalls++ }
                else {
                    val v = DisplayProfileEngine.vsyncBudgetMs
                    val m = Math.round(gap / v).coerceIn(1, 4)
                    bucket[m - 1]++
                }
            }
            lastNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().postFrameCallback(callback)
        }
        val t = Thread {
            while (running) {
                val windowMs = REPORT_EVERY_MS
                try { Thread.sleep(windowMs) } catch (_: Throwable) { return@Thread }
                try {
                    val counted = bucket.sum().coerceAtLeast(1L)
                    val dominant = bucket.max()
                    stabilityPct = dominant * 100f / counted
                    val winMin = windowMs / 60000f
                    stallsPerMin = winStalls / winMin
                    val mix = bucket.joinToString("/") { (it * 100 / counted).toString() }
                    RuntimeLogger.log("frames=" + frames +
                        " mix=" + mix + "% stability=" + String.format("%.0f", stabilityPct) +
                        "% jitter=" + String.format("%.1f", jitterMs) +
                        "ms worst=" + String.format("%.0f", worstGapMs) +
                        "ms stalls/min=" + String.format("%.1f", stallsPerMin) +
                        " total=" + totalStalls, "LAGFRAME")
                    for (i in bucket.indices) bucket[i] = 0L
                    winStalls = 0L; winFrames = 0L; worstGapMs = 0f
                } catch (_: Throwable) { }
            }
        }
        t.isDaemon = true; t.name = "lag-frame-report"; t.start()
    }

    fun stop() {
        running = false
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }
}

// --- MERGED: LagVerdictEngine.kt ---

// No68-C-D36 (false claim corrected — this trailing KDoc is
// LagVerdictEngine's header, physically pasted here by the repo's merged
// file layout): the old "V3 ADMIN-WIRED - every threshold live" line was
// false. LagVerdictEngine's thresholds (POLL_MS, JITTER_MS, STABILITY_PCT,
// CHOKE_STALLS, CHOKE_MTSTALL_MS, CHOKE_SPIKES, CONFIRM_POLLS) are getters
// returning literals and no admin store is read anywhere in that file —
// nothing is re-read each poll. A re-tune changes the literal.

/**
 * The judge: reads every lag measurement and names the state -
 * SMOOTH / JITTERY / CHOKING. A change must be seen on N consecutive polls
 * before it flips (no whipsawing on a single blip). Each poll also
 * publishes the full live lag snapshot the admin Detector reads. The poll
 * rhythm and every threshold line are hard-coded literals, not admin
 * tunables (see No68-C-D36).
 */
