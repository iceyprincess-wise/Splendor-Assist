package com.assistant

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import com.assistant.diagnostic.RuntimeLogger

object PanelWatchEngine {

    // Fixed 5 s sweep interval (a literal, not a live-set value).
    private val POLL_MS: Long get() = 5000L

    @Volatile private var running = false
    private var dm: DisplayManager? = null
    private var listener: DisplayManager.DisplayListener? = null
    // Phase P (P-2): D18 double-start fix. Same shape as P-1: the sweep
    // thread was a local never stored, stop() only cleared `running` — a
    // stop/start inside the 5 s sweep sleep left the old thread alive, it
    // woke, saw running==true and kept sweeping: TWO live sweepers
    // calling onPanelRate. start() now joins the old thread (500 ms) and
    // gives up rather than running two; stop() interrupts the sleep.
    private var sweepThread: Thread? = null

    fun start(ctx: Context) {
        if (running) return
        val old = sweepThread
        if (old != null && old.isAlive) {
            try { old.join(500L) } catch (_: Throwable) { }
            if (old.isAlive) return
        }
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
        sweepThread = t
    }

    private fun apply() {
        val hz = dm?.displays?.firstOrNull()?.refreshRate ?: return
        StutterPulseEngine.onPanelRate(hz)
    }

    fun stop() {
        running = false
        try { sweepThread?.interrupt() } catch (_: Throwable) { }
        try { listener?.let { dm?.unregisterDisplayListener(it) } } catch (_: Throwable) { }
        listener = null
    }
}
