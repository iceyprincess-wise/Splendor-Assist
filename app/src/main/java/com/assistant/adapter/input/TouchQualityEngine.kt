package com.assistant.adapter.input

import android.os.Process
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import java.io.BufferedReader
import java.io.FileReader

/**
 * TouchQualityEngine — HARDWORKING ELIMINATOR for Touch IRQ Stalls.
 *
 * Upgraded for eFootball 2027 (15fps/30fps target on Helio G81-Ultra).
 * A 5-second polling delay means touch unresponsiveness goes undetected for too long.
 * 
 * This engine now:
 * 1. Polls every 500ms for instant detection of kernel touch IRQ starvation.
 * 2. Detects stalls after just 2 cycles (1000ms) instead of 5 cycles (25 seconds).
 * 3. Conditionlessly publishes TOUCH_IRQ_STALL to the Queen Bee (SmartAssist) to trigger survival mode.
 * 4. Forcefully applies URGENT_AUDIO priority to fight HyperOS cgroup throttling during stalls.
 * 5. Removes redundant OOM reading (handled by OomAdaptiveThrottleEngine) to save CPU cycles.
 * 6. Fixes broken Regex for parsing /proc/interrupts.
 */
object TouchQualityEngine {

    private const val POLL_MS = 500L
    private const val STALL_THRESHOLD_CYCLES = 2 // 2 cycles * 500ms = 1000ms max touch unresponsiveness

    @Volatile private var running = false
    @Volatile var irqDropDetected = false; private set

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            // Conditionlessly boost this polling thread immediately
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
            
            var prevIrq = -1L
            var emptyIrqCycles = 0

            while (running) {
                try {
                    val irq = readTouchIrqCount()

                    if (prevIrq >= 0 && irq >= 0) {
                        val delta = irq - prevIrq
                        if (delta == 0L) {
                            emptyIrqCycles++
                            
                            // CONDITIONLESS ACTIVE MITIGATION:
                            // If no touch IRQs for 1000ms, the kernel/CPU is starved by HyperOS.
                            if (emptyIrqCycles >= STALL_THRESHOLD_CYCLES) {
                                if (!irqDropDetected) {
                                    irqDropDetected = true
                                    
                                    // Signal Queen Bee (SmartAssist) to drop non-essential UI work
                                    AdapterSignalBus.publishInput("TOUCH_IRQ_STALL", emptyIrqCycles.toLong())
                                    
                                    // Force boost priority to fight HyperOS cgroup throttling
                                    try {
                                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                                    } catch (_: Throwable) {}
                                    
                                    RuntimeLogger.log("TouchQuality: TOUCH IRQ STALL — no kernel events for ${emptyIrqCycles * POLL_MS}ms. SURVIVAL MODE TRIGGERED.", "INPUT")
                                }
                            }
                        } else {
                            emptyIrqCycles = 0
                            if (irqDropDetected) {
                                irqDropDetected = false
                                // Signal Queen Bee to restore full performance
                                AdapterSignalBus.publishInput("TOUCH_IRQ_RECOVERED", 0L)
                                RuntimeLogger.log("TouchQuality: TOUCH IRQ RECOVERED — kernel events restored.", "INPUT")
                            }
                        }
                    }
                    if (irq >= 0) prevIrq = irq
                } catch (_: Throwable) {}

                try { Thread.sleep(POLL_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "input-touch-quality"
        t.priority = Thread.MAX_PRIORITY
        t.start()
        RuntimeLogger.log("TouchQualityEngine started (Hardworking Eliminator Mode - 500ms cycle)", "INPUT")
    }

    fun stop() { running = false }

    private fun readTouchIrqCount(): Long = try {
        val kw = listOf("touch", "fts", "focal", "hid", "i2c", "input")
        var total = 0L
        var found = false

        BufferedReader(FileReader("/proc/interrupts")).use { br ->
            br.lineSequence().forEach { line ->
                if (kw.any { line.lowercase().contains(it) }) {
                    found = true
                    // Fixed Regex: correctly extracts numbers from /proc/interrupts
                    Regex("""\s+(\d+)""").findAll(line).forEach { total += it.groupValues[1].toLongOrNull() ?: 0L }
                }
            }
        }
        if (found) total else -1L
    } catch (_: Throwable) { -1L }
}
