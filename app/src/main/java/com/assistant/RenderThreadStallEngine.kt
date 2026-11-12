package com.assistant






















import android.os.Handler


import android.os.Looper







import android.view.Choreographer


import com.assistant.diagnostic.RuntimeLogger






























object RenderThreadStallEngine {
    @Volatile private var running = false
    @Volatile var gpuQueueStallsPerMin = 0f; private set
    @Volatile private var winStalls = 0L
    @Volatile private var lastFrameEndNanos = 0L
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameEndNanos > 0L) {
                val gapMs = (frameTimeNanos - lastFrameEndNanos) / 1_000_000f
                val vsync = DisplayProfileEngine.vsyncBudgetMs
                if (gapMs > (vsync * 2.5f)) winStalls++
            }
            lastFrameEndNanos = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    fun start() {
        if (running) return; running = true
        Handler(Looper.getMainLooper()).post { Choreographer.getInstance().postFrameCallback(callback) }
        val t = Thread {
            while (running) {
                try { Thread.sleep(10_000L) } catch (_: Throwable) { return@Thread }
                val winMin = 10_000L / 60000f
                gpuQueueStallsPerMin = winStalls / winMin
                if (winStalls > 0) RuntimeLogger.log("RenderThread/GPU stalls/min=" + String.format("%.1f", gpuQueueStallsPerMin), "LAGRENDER")
                winStalls = 0L
            }
        }
        t.isDaemon = true; t.name = "lag-render-stall"; t.start()
    }
    fun stop() {
        running = false
        Handler(Looper.getMainLooper()).post { Choreographer.getInstance().removeFrameCallback(callback) }
    }
}

// --- NEW: NetJitterEngine ---
