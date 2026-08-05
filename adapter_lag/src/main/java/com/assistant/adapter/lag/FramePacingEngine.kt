package com.assistant.adapter.lag

// V3 AGGRESSIVE - mixture-aware, direct jitter
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.assistant.diagnostic.RuntimeLogger

/**
 * V3: no single-cadence guessing. On an adaptive 90Hz panel running a 30fps
 * game, frames legally arrive at 1x/2x/3x vsync - the ENEMY is irregularity,
 * not any particular multiple. So we grade:
 *   jitterMs   - EWMA of |gap - prevGap| (beat-to-beat wobble, the felt stutter)
 *   stability  - share of frames in the window's dominant vsync bucket
 *   hard stalls - gap > 100ms (absolute; a real freeze at any cadence)
 */
object FramePacingEngine {

    private const val ALPHA = 0.2f
    private const val REPORT_EVERY_MS = 20_000L
    private const val STALL_MS = 100f

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
                avgGapMs = if (avgGapMs == 0f) gap else avgGapMs * (1 - ALPHA) + gap * ALPHA
                if (lastGap > 0f) {
                    val d = Math.abs(gap - lastGap)
                    jitterMs = jitterMs * (1 - ALPHA) + d * ALPHA
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
                try { Thread.sleep(REPORT_EVERY_MS) } catch (_: Throwable) { return@Thread }
                try {
                    val counted = bucket.sum().coerceAtLeast(1L)
                    val dominant = bucket.max()
                    stabilityPct = dominant * 100f / counted
                    val winMin = REPORT_EVERY_MS / 60000f
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
