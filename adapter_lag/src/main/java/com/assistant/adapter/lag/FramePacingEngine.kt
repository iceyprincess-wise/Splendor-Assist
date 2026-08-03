package com.assistant.adapter.lag

// V2.2 PROACTIVE - cadence-aware
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.assistant.diagnostic.RuntimeLogger

/**
 * V2.2: grades jank against the CURRENT EFFECTIVE CADENCE, not the panel max.
 * A 30fps game delivering steady 33.3ms frames on a 90Hz panel is ON TARGET,
 * not jank (V2.1 counted every such frame and pinned the verdict at CHOKING).
 * Cadence = median of the last 32 gaps, quantized to vsync multiples,
 * recomputed every 2s. Jank = falling off that cadence.
 */
object FramePacingEngine {

    private const val ALPHA = 0.2f
    private const val REPORT_EVERY_MS = 20_000L
    private const val RING = 32

    @Volatile private var running = false
    @Volatile var avgGapMs = 0f; private set
    @Volatile var worstGapMs = 0f; private set
    @Volatile var jankPerMin = 0f; private set
    @Volatile var cadenceMs = 0f; private set
    @Volatile private var lastNanos = 0L
    @Volatile private var windowJank = 0L
    @Volatile private var hardStalls = 0L
    @Volatile private var frames = 0L

    private val ring = FloatArray(RING)
    @Volatile private var ringIdx = 0

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNanos > 0L) {
                val gap = (frameTimeNanos - lastNanos) / 1_000_000f
                frames++
                ring[ringIdx % RING] = gap
                ringIdx++
                avgGapMs = if (avgGapMs == 0f) gap else avgGapMs * (1 - ALPHA) + gap * ALPHA
                if (gap > worstGapMs) worstGapMs = gap
                val base = if (cadenceMs > 0f) cadenceMs else DisplayProfileEngine.vsyncBudgetMs
                if (gap > maxOf(base * 3f, 100f)) { hardStalls++; windowJank++ }
                else if (gap > base * 1.75f) windowJank++
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
        // cadence tracker: every 2s, median of ring quantized to vsync multiples
        val c = Thread {
            while (running) {
                try { Thread.sleep(2000) } catch (_: Throwable) { return@Thread }
                try {
                    val n = minOf(ringIdx, RING)
                    if (n >= 8) {
                        val copy = ring.copyOf(n).also { it.sort() }
                        val median = copy[n / 2]
                        val budget = DisplayProfileEngine.vsyncBudgetMs
                        val mult = Math.round(median / budget).coerceIn(1, 6)
                        cadenceMs = mult * budget
                    }
                } catch (_: Throwable) { }
            }
        }
        c.isDaemon = true; c.name = "lag-cadence"; c.start()

        val t = Thread {
            while (running) {
                try { Thread.sleep(REPORT_EVERY_MS) } catch (_: Throwable) { return@Thread }
                val winMin = REPORT_EVERY_MS / 60000f
                jankPerMin = windowJank / winMin
                val effFps = if (cadenceMs > 0f) Math.round(1000f / cadenceMs) else 0
                RuntimeLogger.log("frames=" + frames +
                    " cadence=" + String.format("%.1f", cadenceMs) + "ms/" + effFps + "fps" +
                    " avgGap=" + String.format("%.1f", avgGapMs) +
                    "ms worst=" + String.format("%.0f", worstGapMs) +
                    "ms jank/min=" + String.format("%.1f", jankPerMin) +
                    " hardStalls=" + hardStalls, "LAGFRAME")
                windowJank = 0L
                worstGapMs = 0f
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
