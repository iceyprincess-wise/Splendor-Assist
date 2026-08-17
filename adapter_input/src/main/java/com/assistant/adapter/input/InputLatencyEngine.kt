package com.assistant.adapter.input
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
object InputLatencyEngine {
    @Volatile private var running = false
    @Volatile private var engineStartMs = 0L
    @Volatile var latencyMs = 0L; private set
    @Volatile var classification = "UNKNOWN"; private set
    @Volatile var measurements = 0L; private set
    @Volatile var lagEvents = 0L; private set
    private val mainHandler = Handler(Looper.getMainLooper())
    fun start() {
        if (running) return; running = true
        engineStartMs = System.currentTimeMillis()
        val t = Thread {
            while (running) {
                try { measure() } catch (_: Throwable) {}
                val intervalMs = if (AdapterSignalBus.manualPerformanceEscalation) {
                    100L
                } else {
                    200L
                }
                try { Thread.sleep(intervalMs) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "input-latency"; t.priority = Thread.MAX_PRIORITY; t.start()
        RuntimeLogger.log("InputLatencyEngine started", "INPUT")
    }
    fun stop() { running = false }
    private fun measure() {
        val posted = SystemClock.elapsedRealtime()
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post { latencyMs = SystemClock.elapsedRealtime() - posted; latch.countDown() }
        latch.await(200L, java.util.concurrent.TimeUnit.MILLISECONDS)
        measurements++
        classification = when {
            latencyMs < 16L -> "INSTANT"
            latencyMs < 33L -> "GOOD"
            latencyMs < 66L -> "DELAYED"
            else -> { lagEvents++; "LAGGING" }
        }
        AdapterSignalBus.publishInput(classification, latencyMs)
        if (classification == "LAGGING") {
            if (System.currentTimeMillis()-engineStartMs<6000L) return
            RuntimeLogger.log("INPUT LAG: ${latencyMs}ms (total lag events: $lagEvents)", "INPUT")
            mainHandler.post { }
        }
    }
}
