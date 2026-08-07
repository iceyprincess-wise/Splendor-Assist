package com.assistant.adapter.stutter

// V2 BURST
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.assistant.diagnostic.RuntimeLogger

/**
 * Sub-second burst radar. Lag grades 20s windows; this watches EVERY 1s slice.
 * A burst = 2+ frames in one slice blowing past 2x the real vsync budget
 * (detected from the panel, not the old hardcoded 60Hz). Catches the 3-5
 * frame hiccups a 20s average washes out - the felt micro-stutter.
 */
object StutterPulseEngine {

    @Volatile private var running = false
    @Volatile var vsyncMs = 16.67f; private set
    @Volatile private var lastNanos = 0L
    // current 1s slice
    @Volatile private var sliceStart = 0L
    @Volatile private var sliceBad = 0
    @Volatile private var sliceWorst = 0f
    // outputs
    @Volatile var burstsPerMin = 0f; private set
    @Volatile var lastBurstWorstMs = 0f; private set
    @Volatile var lastBurstFrames = 0; private set
    @Volatile var lastBurstAtMs = 0L; private set
    @Volatile private var minuteBursts = 0
    @Volatile private var minuteStart = 0L

    fun detectPanel(ctx: Context) {
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val hz = dm.displays.firstOrNull()?.refreshRate ?: 60f
            vsyncMs = 1000f / hz
            RuntimeLogger.log("panel=" + String.format("%.0f", hz) +
                "Hz budget=" + String.format("%.1f", vsyncMs) + "ms", "STUTTER")
        } catch (_: Throwable) { }
    }

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            if (lastNanos > 0L) {
                val gap = (t - lastNanos) / 1_000_000f
                val now = System.currentTimeMillis()
                if (sliceStart == 0L) { sliceStart = now; minuteStart = now }
                if (gap > vsyncMs * 2f) {
                    sliceBad++
                    if (gap > sliceWorst) sliceWorst = gap
                }
                if (now - sliceStart >= 1000L) {
                    if (sliceBad >= 2) {          // a real burst, not one late frame
                        minuteBursts++
                        lastBurstWorstMs = sliceWorst
                        lastBurstFrames = sliceBad
                        lastBurstAtMs = now
                        BurstForensicsEngine.record(sliceBad, sliceWorst)
                    }
                    sliceBad = 0; sliceWorst = 0f; sliceStart = now
                }
                if (now - minuteStart >= 60_000L) {
                    burstsPerMin = minuteBursts.toFloat()
                    minuteBursts = 0; minuteStart = now
                }
            }
            lastNanos = t
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    fun stop() {
        running = false
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }
}
