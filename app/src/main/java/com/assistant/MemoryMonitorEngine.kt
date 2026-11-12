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
                        val pct = if (totalMb > 0) (availMb.toFloat() / totalMb.toFloat()) * 100f else 100f
                        val tier = when {
                            info.lowMemory || pct < 15f -> "CRITICAL"
                            pct < 30f -> "PRESSURE"
                            pct < 50f -> "WATCH"
                            else -> "HEALTHY"
                        }
                        MemoryPressureBusEngine.publish(tier, availMb)
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(5000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "mem-monitor"; t.start()
    }

    fun stop() { running = false }
}

/* ========
MemoryPressureBusEngine
======== */
