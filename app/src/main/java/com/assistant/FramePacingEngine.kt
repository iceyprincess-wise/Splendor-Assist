package com.assistant

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.atomic.AtomicBoolean

object FramePacingEngine {

    // HARD-CODED CONSTANTS (No68-C-D36): the old "ADMIN-TUNABLE" header
    // was false — these getters return literals and no admin store is read
    // anywhere in this file. A re-tune changes the literal.
    private val ALPHA: Float get() = 0.2f
    private val REPORT_EVERY_MS: Long get() = 20_000L
    private val STALL_MS: Float get() = 100f

    @Volatile private var running = AtomicBoolean(false)
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
    @Volatile private var reportThread: Thread? = null

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running.get()) return
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
        // T-15 (Phase T, Q-L1): D18 double-start fix — TWO vectors.
        // (1) Callback: the old `if (running) return; running = true`
        // guard was a racy check-then-set, and start() posts the SAME
        // callback object again: two concurrent starts (or a restart
        // while an in-flight doFrame re-posts) register it twice and
        // every frame is then counted twice forever (each doFrame
        // re-posts). StutterPulse's proven shape — atomic getAndSet —
        // closes it: a start while running is a no-op before any post.
        // (2) Report thread ("lag-frame-report", 20 s sleep) was never
        // stored and stop() never interrupted it: a restart inside the
        // sleep window left the old reporter beside the new one, both
        // resetting the 20 s buckets. R-1/R-3 shape: join the old
        // thread 500 ms, give up if alive (running stays true, the old
        // reporter keeps the engine alive on its own, callback already
        // posted above — no second reporter, no lost measurement).
        // Measurement logic (doFrame + the 20 s report) is untouched;
        // LagVerdict + FrameDropPreventionEngine read the same four
        // volatile outputs as before.
        if (running.getAndSet(true)) return
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().postFrameCallback(callback)
        }
        val old = reportThread
        if (old != null && old.isAlive) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        val t = Thread {
            while (running.get()) {
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
        reportThread = t
    }

    fun stop() {
        running.set(false)
        reportThread?.interrupt()
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }
}
