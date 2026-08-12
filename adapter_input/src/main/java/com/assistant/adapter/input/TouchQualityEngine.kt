package com.assistant.adapter.input
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
import java.io.BufferedReader
import java.io.FileReader
object TouchQualityEngine {
    @Volatile private var running = false
    @Volatile var oomScore = 0; private set
    @Volatile var oomWarning = false; private set
    @Volatile var irqDropDetected = false; private set
    fun start() {
        if (running) return; running = true
        val t = Thread {
            var prevIrq = -1L; var emptyIrqCycles = 0
            while (running) {
                try {
                    val oom = readOomScore(Process.myPid()); oomScore = oom; oomWarning = oom > 0
                    if (oomWarning) RuntimeLogger.log("TouchQuality: OOM adj=$oom — HyperOS may throttle us", "INPUT")
                    val irq = readTouchIrqCount()
                    if (prevIrq >= 0 && irq >= 0) {
                        val delta = irq - prevIrq
                        if (delta == 0L) { emptyIrqCycles++
                            if (emptyIrqCycles >= 5) { irqDropDetected = true
                                RuntimeLogger.log("TouchQuality: touch IRQ STALL — no kernel events for ${emptyIrqCycles*5}s", "INPUT") }
                        } else { emptyIrqCycles = 0; irqDropDetected = false }
                    }
                    if (irq >= 0) prevIrq = irq
                } catch (_: Throwable) {}
                try { Thread.sleep(5_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "input-touch-quality"; t.start()
        RuntimeLogger.log("TouchQualityEngine started", "INPUT")
    }
    fun stop() { running = false }
    private fun readOomScore(pid: Int): Int = try {
        BufferedReader(FileReader("/proc/$pid/oom_score_adj")).use { it.readLine()?.trim()?.toIntOrNull() ?: 0 }
    } catch (_: Throwable) { 0 }
    private fun readTouchIrqCount(): Long = try {
        val kw = listOf("touch","fts","focal","hid","i2c","input")
        var total = 0L; var found = false
        BufferedReader(FileReader("/proc/interrupts")).use { br ->
            br.lineSequence().forEach { line ->
                if (kw.any { line.lowercase().contains(it) }) {
                    found = true
                    Regex("""\s+(\d+)""").findAll(line).forEach { total += it.groupValues[1].toLongOrNull() ?: 0L }
                }
            }
        }
        if (found) total else -1L
    } catch (_: Throwable) { -1L }
}
