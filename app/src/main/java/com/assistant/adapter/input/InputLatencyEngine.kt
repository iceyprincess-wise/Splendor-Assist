package com.assistant.adapter.input

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object InputLatencyEngine {

    @Volatile private var running = false
    @Volatile var latencyMs = 0L; private set
    @Volatile var classification = "UNKNOWN"; private set
    @Volatile var measurements = 0L; private set
    @Volatile var lagEvents = 0L; private set

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true

        val t = Thread {
            // Conditionlessly boost this polling thread so it never misses a measurement
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
            
            while (running) {
                try { measure() } catch (_: Throwable) {}
                try { Thread.sleep(200L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-latency"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        
        RuntimeLogger.log("InputLatencyEngine started (Hardworking Eliminator Mode)", "INPUT")
    }

    fun stop() { running = false }

    private fun measure() {
        val posted = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        
        // Post to main thread to measure dispatch latency
        mainHandler.post { 
            latencyMs = SystemClock.elapsedRealtime() - posted
            latch.countDown() 
        }
        
        // Wait max 200ms for main thread to respond
        val responded = latch.await(200L, TimeUnit.MILLISECONDS)
        if (!responded) {
            latencyMs = 200L // Main thread is completely blocked
            latch.countDown() // Prevent leak
        }
        
        measurements++
        
        // 15fps = 66ms/frame. 30fps = 33ms/frame.
        classification = when {
            latencyMs < 16L -> "INSTANT"
            latencyMs < 33L -> "GOOD"
            latencyMs < 66L -> "DELAYED"
            else -> { lagEvents++; "LAGGING" }
        }

        AdapterSignalBus.publishInput(classification, latencyMs)

        // CONDITIONLESS ACTIVE MITIGATION:
        // No warmup delays. If main thread is congested, immediately trigger purges.
        if (latencyMs >= 33L) { // More than 1 frame at 30fps, or half frame at 15fps
            
            // Signal Queen Bee (SmartAssist) to drop non-essential UI work and prioritize input
            AdapterSignalBus.publishInput("MAIN_THREAD_CONGESTION", latencyMs)
            
            // Force boost main thread priority dynamically if heavily lagging
            if (latencyMs >= 66L) {
                try {
                    // Attempt to boost the process priority
                    Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (_: Throwable) {}
                
                RuntimeLogger.log("INPUT CRITICAL LAG: ${latencyMs}ms (total lag events: $lagEvents). PURGE TRIGGERED.", "INPUT")
            }
        }
    }
}
