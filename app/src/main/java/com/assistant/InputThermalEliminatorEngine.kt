package com.assistant







import android.content.Context













import android.os.Build







import android.os.PowerManager
import android.os.Process




import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger






























object InputThermalEliminatorEngine {

    @Volatile private var running = false
    @Volatile var thermalStatus = "UNKNOWN"; private set

    // Phase I (I-4): same D18 class as InputLatencyEngine (no68) — stop()
    // only cleared the flag, so a restart within the 1000ms sleep left the
    // old thread polling alongside the new one. Keep the thread so stop()
    // can wake it and start() can join it.
    @Volatile private var pollThread: Thread? = null

    fun start(context: Context) {
        if (running) return
        // Phase I (I-4): a previous poll thread still winding down must be
        // dead before a new one starts — otherwise two threads poll and
        // publish concurrently. Wait briefly; give up (no start) rather
        // than run two pollers. (Same pattern as InputLatencyEngine D18.)
        val old = pollThread
        if (old?.isAlive == true) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        running = true
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val t = Thread {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            
            var lastStatus = "UNKNOWN"
            while (running) {
                try {
                    val status = if (Build.VERSION.SDK_INT >= 29 && pm != null) {
                        when (pm.currentThermalStatus) {
                            PowerManager.THERMAL_STATUS_NONE -> "NONE"
                            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                            else -> "UNKNOWN"
                        }
                    } else "UNKNOWN"
                    
                    thermalStatus = status
                    
                    if (status != lastStatus) {
                        lastStatus = status
                        
                        // CONDITIONLESS ACTIVE MITIGATION:
                        // publishInput latency abuse (batch 5): the old code
                        // passed status.hashCode() as latencyMs — a hash is
                        // not milliseconds. No latency is measured on a
                        // thermal state change, so 0L on both branches.
                        if (status == "MODERATE" || status == "SEVERE" || status == "CRITICAL" || status == "EMERGENCY" || status == "SHUTDOWN") {
                            AdapterSignalBus.publishInput("THERMAL_THROTTLE_ACTIVE", 0L)
                            // U2 (item 26, g54a): cross-process copy for the mirror.
                            try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceInput("THERMAL_THROTTLE_ACTIVE") } catch (_: Throwable) {}
                            RuntimeLogger.log("THERMAL THROTTLE DETECTED: $status — Signaling Queen Bee for Survival Mode", "INPUT")
                        } else {
                            AdapterSignalBus.publishInput("THERMAL_THROTTLE_CLEAR", 0L)
                            // U2 (item 26, g54a): clear the mirrored value too.
                            try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceInput("THERMAL_THROTTLE_CLEAR") } catch (_: Throwable) {}
                            RuntimeLogger.log("THERMAL THROTTLE CLEARED: $status — Restoring full performance", "INPUT")
                        }
                    }
                } catch (_: Throwable) {}
                
                try { Thread.sleep(1000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-thermal-eliminator"
        t.priority = Thread.MAX_PRIORITY
        pollThread = t
        t.start()
        RuntimeLogger.log("InputThermalEliminatorEngine started (Hardworking Eliminator Mode)", "INPUT")
    }

    fun stop() {
        running = false
        // Phase I (I-4): wake the sleeper so it exits now instead of up
        // to 1000ms later (and cannot be resurrected by a quick restart).
        pollThread?.interrupt()
    }
}

/* ========
InputVsyncEliminatorEngine
======== */
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
