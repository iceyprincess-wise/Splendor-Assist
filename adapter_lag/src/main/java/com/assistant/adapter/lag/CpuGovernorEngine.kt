package com.assistant.adapter.lag

import android.os.Process
import com.assistant.diagnostic.RuntimeLogger

/**
 * CpuGovernorEngine — Reserve A75 fast cores for eFootball, park Splendor on A55.
 * Helio G81-Ultra: cores 0-5 = A55 (efficiency), cores 6-7 = A75 (performance).
 * Without root: scheduler priority-only mode (still very effective).
 * With root/sysfs: sets actual cpufreq governor per cluster.
 */
object CpuGovernorEngine {
    @Volatile private var running = false
    @Volatile var mode = "STARTING"; private set

    fun start() {
        if (running) return
        running = true
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
        val t = Thread {
            var logged = false
            while (running) {
                try {
                    var wrote = false
                    for (core in 6..7) {
                        val gov = java.io.File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_governor")
                        if (gov.canWrite()) { gov.writeText("performance"); wrote = true }
                    }
                    for (core in 0..5) {
                        val gov = java.io.File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_governor")
                        if (gov.canWrite()) gov.writeText("schedutil")
                    }
                    if (!logged) {
                        mode = if (wrote) "SYSFS_ACTIVE" else "PRIORITY_ONLY"
                        RuntimeLogger.log("CpuGovernor mode=$mode (A75=game A55=splendor)", "CPUGOV")
                        logged = true
                    }
                } catch (e: Throwable) {
                    if (!logged) {
                        mode = "PRIORITY_ONLY"
                        RuntimeLogger.log("CpuGovernor: priority-only (${e.javaClass.simpleName})", "CPUGOV")
                        logged = true
                    }
                }
                try { Thread.sleep(30_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-cpu-gov"; t.priority = Thread.MIN_PRIORITY; t.start()
        RuntimeLogger.log("CpuGovernorEngine started", "CPUGOV")
    }

    fun stop() { running = false; mode = "STOPPED" }
}
