package com.assistant

import android.os.Process
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import java.io.BufferedReader
import java.io.FileReader

object TouchQualityEngine {

    private const val POLL_MS = 500L
    private const val STALL_THRESHOLD_CYCLES = 2 // 2 cycles * 500ms = 1000ms max touch unresponsiveness

    // No68-I-D31: the Regex was constructed for EVERY line of
    // /proc/interrupts on EVERY 500 ms poll. Hoist it to one instance.
    private val IRQ_NUMBER_REGEX = Regex("""\s+(\d+)""")

    @Volatile private var running = false
    @Volatile var irqDropDetected = false; private set
    // T-2 (Phase T): D18 double-start fix — the poller used to live in a
    // local, so stop() could not interrupt it and a restart inside the
    // 500 ms sleep left the old thread polling alongside the new one.
    @Volatile private var pollThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        // T-2: join the old poller (500 ms) and give up rather than run two.
        val old = pollThread
        if (old != null && old.isAlive) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
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
                            // No68-I-D29: zero IRQs only mean "stall" while the
                            // user is actually touching. The IRQ counter moves
                            // on touch events, so a user who simply is not
                            // touching for > 1 s used to raise a false STALL
                            // (and the survival-mode signal) every idle second.
                            // UserTouchState.isTouching() is true through a
                            // hold (the touching flag) and for 2.5 s after the
                            // last touch, which covers the 1 s detection
                            // window; during a genuine stall the END event
                            // never arrives, so the flag stays true and the
                            // stall is still caught in 2 cycles. Not touching
                            // resets the count: no touch, no evidence.
                            if (UserTouchState.isTouching()) {
                                emptyIrqCycles++

                                // CONDITIONLESS ACTIVE MITIGATION:
                                // If no touch IRQs for 1000ms, the kernel/CPU is starved by HyperOS.
                                if (emptyIrqCycles >= STALL_THRESHOLD_CYCLES) {
                                    if (!irqDropDetected) {
                                        irqDropDetected = true

                                        // Signal Queen Bee (SmartAssist) to drop non-essential UI work
                                        // publishInput latency abuse (batch 5):
                                        // the old code passed emptyIrqCycles
                                        // (a count) as latencyMs. The real
                                        // latency is the stall duration the
                                        // log line below already prints:
                                        // cycles x poll interval in ms.
                                        AdapterSignalBus.publishInput("TOUCH_IRQ_STALL", emptyIrqCycles * POLL_MS)
                                        // L-F02 (item 26 lead): mirror to the registry DeviceState
                                        // so MainProcessBusMirror can copy TOUCH_IRQ_STALL to the
                                        // main process (0.7x revival). Same pattern as
                                        // InputVsyncEliminatorEngine's VSYNC_STARVATION publish.
                                        try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceInput("TOUCH_IRQ_STALL") } catch (_: Throwable) {}

                                        // Force boost priority to fight HyperOS cgroup throttling
                                        try {
                                            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                                        } catch (_: Throwable) {}

                                        RuntimeLogger.log("TouchQuality: TOUCH IRQ STALL — no kernel events for ${emptyIrqCycles * POLL_MS}ms. SURVIVAL MODE TRIGGERED.", "INPUT")
                                    }
                                }
                            } else {
                                emptyIrqCycles = 0
                            }
                        } else {
                            emptyIrqCycles = 0
                            if (irqDropDetected) {
                                irqDropDetected = false
                                // Signal Queen Bee to restore full performance
                                AdapterSignalBus.publishInput("TOUCH_IRQ_RECOVERED", 0L)
                                // L-F02 (item 26 lead): mirror the recovery too, so the
                                // main-process copy clears when the stall clears.
                                try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishDeviceInput("TOUCH_IRQ_RECOVERED") } catch (_: Throwable) {}
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
        pollThread = t
        RuntimeLogger.log("TouchQualityEngine started (Hardworking Eliminator Mode - 500ms cycle)", "INPUT")
    }

    fun stop() {
        running = false
        pollThread?.interrupt()
    }

    private fun readTouchIrqCount(): Long = try {
        val kw = listOf("touch", "fts", "focal", "hid", "i2c", "input")
        var total = 0L
        var found = false

        BufferedReader(FileReader("/proc/interrupts")).use { br ->
            br.lineSequence().forEach { line ->
                if (kw.any { line.lowercase().contains(it) }) {
                    found = true
                    // Fixed Regex: correctly extracts numbers from /proc/interrupts
                    IRQ_NUMBER_REGEX.findAll(line).forEach { total += it.groupValues[1].toLongOrNull() ?: 0L }
                }
            }
        }
        if (found) total else -1L
    } catch (_: Throwable) { -1L }
}
