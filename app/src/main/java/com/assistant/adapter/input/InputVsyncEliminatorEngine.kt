package com.assistant.adapter.input

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

/**
 * InputVsyncEliminatorEngine — HARDWORKING ELIMINATOR for Vsync/Choreographer Starvation.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * If SurfaceFlinger (GPU compositor) is overloaded, Choreographer frame callbacks are delayed.
 * This causes input-to-display lag even if the main thread dispatch latency is low.
 *
 * This engine monitors Vsync delays every 1000ms. If the frame callback is delayed by > 66ms
 * (1 frame at 15fps), it signals the Queen Bee to simplify UI and free up the compositor.
 */
object InputVsyncEliminatorEngine {

    @Volatile private var running = false
    @Volatile var lastFrameDelayMs = 0L; private set

    fun start() {
        if (running) return
        running = true
        
        val handler = Handler(Looper.getMainLooper())
        val checkIntervalMs = 1000L
        
        val checkRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                
                val startMs = SystemClock.elapsedRealtime()
                try {
                    Choreographer.getInstance().postFrameCallback {
                        if (!running) return@postFrameCallback
                        
                        val callbackMs = SystemClock.elapsedRealtime()
                        val delay = callbackMs - startMs
                        lastFrameDelayMs = delay
                        
                        // CONDITIONLESS ACTIVE MITIGATION:
                        // If frame callback is delayed by > 66ms (1 frame at 15fps)
                        if (delay > 66L) {
                            AdapterSignalBus.publishInput("VSYNC_STARVATION", delay)
                            // Force boost main thread priority to clear compositor backlog
                            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
                        } else {
                            AdapterSignalBus.publishInput("VSYNC_OK", delay)
                        }
                    }
                } catch (_: Throwable) {}
                
                if (running) {
                    handler.postDelayed(this, checkIntervalMs)
                }
            }
        }
        
        handler.post(checkRunnable)
        RuntimeLogger.log("InputVsyncEliminatorEngine started (Hardworking Eliminator Mode)", "INPUT")
    }

    fun stop() { running = false }
}
