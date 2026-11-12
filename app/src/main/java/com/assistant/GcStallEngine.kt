package com.assistant




































import com.assistant.diagnostic.RuntimeLogger

























import kotlin.math.max




object GcStallEngine {
    @Volatile private var running = false
    @Volatile var memoryPressure = "LOW"; private set
    fun start() {
        if (running) return; running = true
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
    }
    fun stop() { running = false }
}

// --- NEW: RenderThreadStallEngine ---
