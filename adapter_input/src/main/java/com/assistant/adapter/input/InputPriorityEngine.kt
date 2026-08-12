package com.assistant.adapter.input
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
object InputPriorityEngine {
    @Volatile private var running = false
    @Volatile var reapplyCount = 0; private set
    fun start() {
        if (running) return; running = true
        applyPriority()
        val t = Thread {
            while (running) {
                try {
                    val cur = Process.getThreadPriority(Process.myTid())
                    if (cur > Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                        reapplyCount++
                        RuntimeLogger.log("InputPriority: reset to $cur by OS, reapplying (#$reapplyCount)", "INPUT")
                        applyPriority()
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(30_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "input-priority"; t.start()
        RuntimeLogger.log("InputPriorityEngine started — URGENT_DISPLAY secured", "INPUT")
    }
    fun stop() { running = false }
    private fun applyPriority() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
              RuntimeLogger.log("InputPriority: URGENT_DISPLAY applied", "INPUT") }
        catch (e: Throwable) { RuntimeLogger.log("InputPriority: failed: ${e.message}", "INPUT") }
    }
}
