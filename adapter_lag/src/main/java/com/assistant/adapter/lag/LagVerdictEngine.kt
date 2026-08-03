package com.assistant.adapter.lag

// V2.2 PROACTIVE - cadence-aware verdict
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * V2.2: SMOOTH means "holding the game's own cadence" - 30fps on target is
 * SMOOTH even on a 90Hz panel. CHOKING means falling OFF that cadence:
 * gap blowing past it, stall storms, or jank bursts.
 */
object LagVerdictEngine {

    @Volatile private var running = false
    @Volatile var verdict = "UNKNOWN"; private set
    @Volatile private var flips = 0L
    @Volatile private var lastHeartbeat = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val cadence = if (FramePacingEngine.cadenceMs > 0f)
                        FramePacingEngine.cadenceMs else DisplayProfileEngine.vsyncBudgetMs
                    val gap = FramePacingEngine.avgGapMs
                    val stall = MainThreadStallEngine.avgLatenessMs
                    val jank = FramePacingEngine.jankPerMin
                    val spm = MainThreadStallEngine.spikesPerMin
                    val next = when {
                        gap > cadence * 2.5f || stall > 120f || jank > 30f || spm > 20f -> "CHOKING"
                        gap > cadence * 1.5f || stall > 50f || jank > 10f || spm > 6f -> "STRAINED"
                        gap > 0f -> "SMOOTH"
                        else -> "UNKNOWN"
                    }
                    val now = System.currentTimeMillis()
                    val effFps = Math.round(1000f / cadence)
                    if (next != verdict) {
                        flips++
                        RuntimeLogger.log("DEVICE " + verdict + " -> " + next +
                            " (cadence=" + effFps + "fps gap=" + String.format("%.1f", gap) +
                            "ms stall=" + String.format("%.0f", stall) +
                            "ms jank/min=" + String.format("%.1f", jank) +
                            " stalls/min=" + String.format("%.1f", spm) + ")", "LAGVERDICT")
                        verdict = next
                        lastHeartbeat = now
                    } else if (now - lastHeartbeat >= 60_000L) {
                        lastHeartbeat = now
                        RuntimeLogger.log("DEVICE " + verdict +
                            " (cadence=" + effFps + "fps gap=" + String.format("%.1f", gap) +
                            "ms jank/min=" + String.format("%.1f", jank) + ")", "LAGVERDICT")
                    }
                    PerformanceTelemetryRegistry.publishDisplay(gap, jank, stall, verdict)
                } catch (_: Throwable) { }
                try { Thread.sleep(2000) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-verdict"; t.start()
    }

    fun stop() { running = false }
}
