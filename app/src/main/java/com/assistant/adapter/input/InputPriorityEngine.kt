package com.assistant.adapter.input

import android.os.Process
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

/**
 * InputPriorityEngine — HARDWORKING BOOSTER & ELIMINATOR.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * HyperOS aggressively throttles threads during fast gameplay to save battery.
 * Waiting 30 seconds to reapply priority causes massive input lag.
 * 
 * This engine now conditionlessly reapplies URGENT_DISPLAY every 500ms
 * to prevent silent deprioritization and instantly blocks OS throttle attempts.
 */
object InputPriorityEngine {

    @Volatile private var running = false
    @Volatile var reapplyCount = 0; private set

    fun start() {
        if (running) return
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
                        AdapterSignalBus.publishInput("OS_THROTTLE_BLOCKED", reapplyCount.toLong())
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
        t.start()

        RuntimeLogger.log("InputPriorityEngine started — HARDWORKING BOOSTER MODE (500ms cycle)", "INPUT")
    }

    fun stop() { running = false }

    private fun applyPriority() {
        try { 
            // Force URGENT_DISPLAY (-8) to ensure input processing is never starved
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (e: Throwable) { 
            RuntimeLogger.log("InputPriority: failed: ${e.message}", "INPUT") 
        }
    }
}
