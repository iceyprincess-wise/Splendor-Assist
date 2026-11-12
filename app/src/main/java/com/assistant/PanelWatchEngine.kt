package com.assistant







import android.content.Context



import android.hardware.display.DisplayManager










import android.os.Handler


import android.os.Looper










import com.assistant.diagnostic.RuntimeLogger






























object PanelWatchEngine {

    // ADMIN-TUNABLE
    private val POLL_MS: Long get() = 5000L

    @Volatile private var running = false
    private var dm: DisplayManager? = null
    private var listener: DisplayManager.DisplayListener? = null

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        try {
            val mgr = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm = mgr
            val l = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) { }
                override fun onDisplayRemoved(displayId: Int) { }
                override fun onDisplayChanged(displayId: Int) { apply() }
            }
            mgr.registerDisplayListener(l, Handler(Looper.getMainLooper()))
            listener = l
            RuntimeLogger.log("instant panel-change listener registered", "STUTTER")
        } catch (t: Throwable) {
            RuntimeLogger.log("panel listener unavailable, sweep only: " + t.message, "STUTTER")
        }
        val t = Thread {
            while (running) {
                try { apply() } catch (_: Throwable) { }
                val nap = POLL_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "stutter-panelwatch"; t.start()
    }

    private fun apply() {
        val hz = dm?.displays?.firstOrNull()?.refreshRate ?: return
        StutterPulseEngine.onPanelRate(hz)
    }

    fun stop() {
        running = false
        try { listener?.let { dm?.unregisterDisplayListener(it) } } catch (_: Throwable) { }
        listener = null
    }
}


/* ========
StutterPulseEngine
======== */
// V3 ADMIN-WIRED - every knob answers the admin store live, publishes for the Detector

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
