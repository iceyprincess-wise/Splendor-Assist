package com.assistant


import android.app.ActivityManager




import android.content.Context



























































object MemoryMonitorEngine {
    @Volatile private var running = false
    private var appContext: android.content.Context? = null

    fun start(ctx: android.content.Context) {
        if (running) return
        running = true
        appContext = ctx.applicationContext
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
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(5000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "mem-monitor"; t.start()
    }

    fun stop() { running = false }

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

/* ========
MemoryPressureBusEngine
======== */
