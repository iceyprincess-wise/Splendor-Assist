package com.assistant

import com.assistant.diagnostic.RuntimeLogger

object GcStallEngine {
    @Volatile private var running = false
    @Volatile private var pollThread: Thread? = null
    @Volatile var memoryPressure = "LOW"; private set
    fun start() {
        if (running) return; running = true
        // T-14 (Phase T, Q-L1): D18 double-start fix, R-1/R-3 shape.
        // The old stop() was bare `running = false` with no stored
        // thread and no interrupt: a restart inside the 5 s sleep left
        // the old poller alive beside the new one (both writing
        // memoryPressure + logging). Join the old thread 500 ms and
        // give up — with running left true the old poller keeps the
        // engine alive on its own, so start() is a safe no-op.
        val old = pollThread
        if (old != null && old.isAlive) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        val t = Thread {
            while (running) {
                try {
                    val rt = Runtime.getRuntime()
                    val used = rt.totalMemory() - rt.freeMemory()
                    val max = rt.maxMemory()
                    val pct = (used * 100) / max
                    val newPressure = if (pct > 85) "CRITICAL" else if (pct > 70) "HIGH" else "LOW"
                    if (newPressure != memoryPressure) {
                        memoryPressure = newPressure
                        RuntimeLogger.log("GC/MEM Pressure -> $newPressure ($pct%)", "LAGGC")
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(5000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-gc-stall"; t.start()
        pollThread = t
    }
    fun stop() { running = false; pollThread?.interrupt() }
}
