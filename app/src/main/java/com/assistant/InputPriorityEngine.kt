package com.assistant






























import android.os.Process




import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger






























object InputPriorityEngine {

    @Volatile private var running = false
    @Volatile var reapplyCount = 0; private set

    // Phase I (I-2): same D18 class as InputLatencyEngine (no68) — stop()
    // only cleared the flag, so a restart within the 500ms sleep left the
    // old thread polling alongside the new one. Keep the thread so stop()
    // can wake it and start() can join it.
    @Volatile private var pollThread: Thread? = null

    fun start() {
        if (running) return
        // Phase I (I-2): a previous poll thread still winding down must be
        // dead before a new one starts — otherwise two threads poll and
        // publish concurrently. Wait briefly; give up (no start) rather
        // than run two pollers. (Same pattern as InputLatencyEngine D18.)
        val old = pollThread
        if (old?.isAlive == true) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        running = true
        applyPriority()

        val t = Thread {
            // Conditionlessly boost this polling thread immediately
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
            
            while (running) {
                try {
                    val cur = Process.getThreadPriority(Process.myTid())
                    
                    // CONDITIONLESS ACTIVE MITIGATION:
                    // If HyperOS explicitly resets priority, block it and signal the Queen Bee.
                    if (cur > Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                        reapplyCount++
                        applyPriority()
                        // publishInput latency abuse (batch 5): the old
                        // code passed reapplyCount (a count) as latencyMs.
                        // No latency is measured here — a priority reapply
                        // is a state change, not a timed dispatch — so 0L.
                        AdapterSignalBus.publishInput("OS_THROTTLE_BLOCKED", 0L)
                        RuntimeLogger.log("InputPriority: reset to $cur by OS, reapplying (#$reapplyCount)", "INPUT")
                    } else {
                        // Conditionlessly reapply anyway to fight silent HyperOS battery saver throttling
                        applyPriority()
                    }
                } catch (_: Throwable) {}
                
                // Reduced from 30,000ms to 500ms for tight 15fps/30fps reaction
                try { Thread.sleep(500L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-priority"
        t.priority = Thread.MAX_PRIORITY
        pollThread = t
        t.start()

        RuntimeLogger.log("InputPriorityEngine started — HARDWORKING BOOSTER MODE (500ms cycle)", "INPUT")
    }

    fun stop() {
        running = false
        // Phase I (I-2): wake the sleeper so it exits now instead of up
        // to 500ms later (and cannot be resurrected by a quick restart).
        pollThread?.interrupt()
    }

    private fun applyPriority() {
        try { 
            // Force URGENT_DISPLAY (-8) to ensure input processing is never starved
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (e: Throwable) { 
            RuntimeLogger.log("InputPriority: failed: ${e.message}", "INPUT") 
        }
    }
}

/* ========
InputThermalEliminatorEngine
======== */
/**
 * InputThermalEliminatorEngine — HARDWORKING ELIMINATOR for Thermal Throttling.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * When the Helio G81-Ultra heats up, the kernel aggressively caps CPU frequency.
 * Thread priority (URGENT_AUDIO) CANNOT overcome a frequency cap.
 * 
 * This engine polls every 1000ms. If thermal status >= MODERATE, it signals
 * the Queen Bee (SmartAssist) to drop non-essential work and reduce CPU load,
 * allowing the device to cool down and preventing total input lockout.
 */
