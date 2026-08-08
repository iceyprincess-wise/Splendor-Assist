package com.assistant.adapter.stutter

// V3 ADMIN-WIRED - every knob answers the admin store live, publishes for the Detector
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.assistant.admin.AdminConfigStore
import com.assistant.admin.AdminLiveStats
import com.assistant.diagnostic.RuntimeLogger

/**
 * Sub-second burst radar. Lag grades long windows; this watches EVERY slice
 * (default 1s). A burst = enough frames in one slice blowing past the burst
 * line (screen beat x multiplier, beat read from the REAL panel and updated
 * live by PanelWatchEngine). Catches the 3-5 frame hiccups an average washes
 * out - the felt micro-stutter.
 *
 * V3: burst line, frames-per-slice, slice length and readout rhythm are all
 * admin-tunable and re-read every frame - values apply instantly, no
 * restart. Publishes live burst truth for the admin Detector.
 */
object StutterPulseEngine {

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val BURST_MULT: Float get() = AdminConfigStore.get("stutter.pulse.burst_mult", 2f)
    private val MIN_FRAMES: Int get() = AdminConfigStore.getInt("stutter.pulse.min_frames", 2)
    private val SLICE_MS: Long get() = AdminConfigStore.getLong("stutter.pulse.slice_ms", 1000L)
    private val PUBLISH_MS: Long get() = AdminConfigStore.getLong("stutter.pulse.publish_ms", 5000L)

    @Volatile private var running = false
    @Volatile var vsyncMs = 16.67f; private set
    @Volatile var panelHz = 60f; private set
    @Volatile private var lastNanos = 0L
    // current slice
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
            onPanelRate(hz)
        } catch (_: Throwable) { }
    }

    /** Called by PanelWatchEngine the instant the panel changes its rhythm. */
    fun onPanelRate(hz: Float) {
        if (hz <= 0f) return
        if (Math.abs(hz - panelHz) < 0.5f) return
        panelHz = hz
        vsyncMs = 1000f / hz
        RuntimeLogger.log("panel=" + String.format("%.0f", hz) +
            "Hz budget=" + String.format("%.1f", vsyncMs) + "ms (radar re-tuned live)", "STUTTER")
    }

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            if (lastNanos > 0L) {
                val gap = (t - lastNanos) / 1_000_000f
                val now = System.currentTimeMillis()
                if (sliceStart == 0L) { sliceStart = now; minuteStart = now }
                if (gap > vsyncMs * BURST_MULT) {
                    sliceBad++
                    if (gap > sliceWorst) sliceWorst = gap
                }
                val slice = if (SLICE_MS > 0) SLICE_MS else 1L
                if (now - sliceStart >= slice) {
                    val need = if (MIN_FRAMES < 1) 1 else MIN_FRAMES
                    if (sliceBad >= need) {       // a real burst, not one late frame
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
        // live readout for the admin Detector
        val t = Thread {
            while (running) {
                try {
                    AdminLiveStats.publishStutter(
                        burstsPerMin, lastBurstWorstMs, lastBurstFrames,
                        BurstForensicsEngine.state, panelHz)
                } catch (_: Throwable) { }
                val nap = PUBLISH_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "stutter-publish"; t.start()
    }

    fun stop() {
        running = false
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }
}
