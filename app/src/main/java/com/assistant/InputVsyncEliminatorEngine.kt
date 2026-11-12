package com.assistant






















import android.os.Handler


import android.os.Looper




import android.os.Process
import android.os.SystemClock

import android.view.Choreographer

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger






























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

/* ========
OomAdaptiveThrottleEngine
======== */
/**
 * OomAdaptiveThrottleEngine — HARDWORKING ELIMINATOR & BOOSTER for HyperOS OOM/Cgroup throttling.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * HyperOS aggressively throttles apps via cgroups and OOM adj adjustments during fast gameplay.
 * A 3-second polling delay causes up to 45 frames of input lag at 15fps.
 *
 * This engine now:
 * 1. Polls every 500ms for instant detection of HyperOS throttling.
 * 2. Conditionlessly forces THREAD_PRIORITY_URGENT_AUDIO (-19) every cycle to fight silent cgroup throttling.
 * 3. Triggers OOM_HOSTILE at adj >= 10 (ultra-aggressive threshold).
 * 4. Signals the Queen Bee (SmartAssist) to drop non-essential UI work when hostile.
 */
