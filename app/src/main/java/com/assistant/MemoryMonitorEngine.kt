package com.assistant

import android.app.ActivityManager

import android.content.Context

object MemoryMonitorEngine {
    @Volatile private var running = false
    @Volatile private var pollThread: Thread? = null
    private var appContext: android.content.Context? = null

    fun start(ctx: android.content.Context) {
        if (running) return
        // Phase M (M-5): D18-class double-start — a previous poll thread
        // still winding down must be dead before a new one starts, or two
        // pollers publish tiers, attribute drops and evict caches side by
        // side. Wait briefly; give up (no start) rather than run two
        // pollers. (Same pattern as LagVerdictEngine Phase L L-1; the
        // restart is real: AdapterRecoveryEngine relaunches
        // PerformanceEngineService in-process on a stale heartbeat, and
        // onCreate re-ignites these singleton objects.)
        val old = pollThread
        if (old?.isAlive == true) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        running = true
        appContext = ctx.applicationContext
        // Batch 6e: OptimizerLivenessReporter.reportStartup() had zero
        // callers — the one-line startup state report never ran, while
        // reportPeriodic() is called from this engine's poll below. Log
        // the optimizer state once at monitor startup.
        OptimizerLivenessReporter.reportStartup()
        val t = Thread {
            while (running) {
                try {
                    val am = appContext?.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    if (am != null) {
                        val info = android.app.ActivityManager.MemoryInfo()
                        am.getMemoryInfo(info)
                        val availMb = info.availMem / 1048576L
                        val totalMb = info.totalMem / 1048576L
                        val tier = tierFor(
                            availMb, totalMb,
                            info.threshold / 1048576L, info.lowMemory
                        )
                        MemoryPressureBusEngine.publish(tier, availMb)
                        // P4 (batch 3): on CRITICAL, free RAM from caches
                        // no gameplay engine reads (pooled OCR snapshots
                        // + queued routine log lines). At most once a
                        // minute; the log line appears only when bytes
                        // were actually freed. Capture rate is never
                        // touched here (D1).
                        if (tier == "CRITICAL") {
                            try {
                                NonEssentialCacheEvictor.evictIfDue(System.currentTimeMillis())?.let { report ->
                                    if (report.freedBytes > 0L) {
                                        com.assistant.diagnostic.RuntimeLogger.log(
                                            NonEssentialCacheEvictor.logLine(report), "MEMORY"
                                        )
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                        // L7 (batch 1): attribute every avail drop — the
                        // next memory event names its processes instead of
                        // leaving a ~900 MB drop unexplained (Q-N7-3).
                        // PSS reads are best-effort: -1 when unreadable
                        // (another uid's process), never fabricated.
                        attributeAvailDrop(am, availMb)
                        // L8 (batch 1): the periodic optimizer liveness
                        // line (throttled to 1/min inside the reporter).
                        OptimizerLivenessReporter.reportPeriodic()
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(5000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "mem-monitor"; pollThread = t; t.start()
    }

    fun stop() {
        running = false
        // Phase M (M-5): wake the sleeper so it exits now instead of up
        // to 5 s later (and cannot be resurrected by a quick restart).
        pollThread?.interrupt()
    }

    // L7 (batch 1): per-process PSS snapshot for attribution. Own PSS via
    // Debug.getPss(); :survival + game via getProcessMemoryInfo (returns
    // nothing for processes Android will not report — those read -1).
    @Volatile private var prevAvailMb: Long = -1L
    @Volatile private var prevAppPssMb: Long = -1L
    @Volatile private var prevSurvivalPssMb: Long = -1L
    @Volatile private var prevGamePssMb: Long = -1L

    private fun processPssMb(am: android.app.ActivityManager, processNameSuffix: String): Long {
        return try {
            val processes = am.runningAppProcesses ?: return -1L
            val pid = processes.firstOrNull { it.processName.endsWith(processNameSuffix) }?.pid ?: return -1L
            val infos = am.getProcessMemoryInfo(intArrayOf(pid))
            val info = infos?.firstOrNull() ?: return -1L
            // totalPss is in kB.
            info.totalPss / 1024L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun attributeAvailDrop(am: android.app.ActivityManager, availMb: Long) {
        val appPssMb = try { android.os.Debug.getPss() / 1024L } catch (_: Throwable) { -1L }
        val survivalPssMb = processPssMb(am, ":survival")
        val gamePssMb = processPssMb(am, "jp.konami.pesam")
        val prevAvail = prevAvailMb
        if (prevAvail > 0L && availMb < prevAvail) {
            val attribution = MemoryAttributionEngine.attribute(
                prevAvail, availMb,
                prevAppPssMb, appPssMb,
                prevSurvivalPssMb, survivalPssMb,
                prevGamePssMb, gamePssMb
            )
            // Phase M (M-10): keep the latest attribution for the
            // diagnostics screen (DashboardInjector "Memory attribution:"
            // line) alongside the log call below.
            MemoryAttributionEngine.lastAttribution = attribution
            try {
                com.assistant.diagnostic.RuntimeLogger.log(
                    MemoryAttributionEngine.logLine(attribution), "MEMORY"
                )
            } catch (_: Throwable) { }
        }
        prevAvailMb = availMb
        prevAppPssMb = appPssMb
        prevSurvivalPssMb = survivalPssMb
        prevGamePssMb = gamePssMb
    }

    // N7: the old rule tiered CRITICAL at 15% of totalMem (~549 MB on the
    // 4 GB Redmi 15C) while the OS low-memory threshold is 216 MB and
    // lowMemory stayed false all session — the label fired ~2.5x above the
    // OS line with no OOM. Anchor CRITICAL to the OS threshold itself, the
    // same definition MemoryStabilityOptimizer already uses
    // (threshold * 1.15, ~248 MB here). PRESSURE/WATCH keep the existing
    // percentage bands — the tracer's proposed threshold-x3 PRESSURE band
    // was NOT adopted (needs Splendor's confirmation, Q-N7-6).
    // On the observed session this re-tiers 19:09-19:32 from CRITICAL to
    // PRESSURE (strained, not critical) — the honest label.
    internal fun tierFor(
        availMb: Long, totalMb: Long, thresholdMb: Long, lowMemory: Boolean
    ): String {
        val pct = if (totalMb > 0) (availMb.toFloat() / totalMb.toFloat()) * 100f else 100f
        return when {
            lowMemory || availMb <= (thresholdMb * 115) / 100 -> "CRITICAL"
            pct < 30f -> "PRESSURE"
            pct < 50f -> "WATCH"
            else -> "HEALTHY"
        }
    }
}
