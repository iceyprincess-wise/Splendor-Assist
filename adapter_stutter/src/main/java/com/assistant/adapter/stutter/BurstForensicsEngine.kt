package com.assistant.adapter.stutter

// V2 BURST
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * Classifies each burst and keeps a 60s picture:
 *   HICCUP      isolated burst, recovers next slice
 *   OSCILLATION 3+ bursts inside 15s - the rhythmic micro-stutter
 *   SEIZURE     burst with a frame past 150ms - a felt freeze
 * Publishes one state for the governor's FAST path. Aggregated logging only -
 * one line per classification change or per 30s, never per spike.
 */
object BurstForensicsEngine {

    @Volatile var state = "CALM"; private set
    @Volatile private var lastLogMs = 0L
    private val recent = ArrayDeque<Long>()   // burst timestamps, last 60s

    @Synchronized
    fun record(frames: Int, worstMs: Float) {
        val now = System.currentTimeMillis()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > 60_000L) recent.removeFirst()
        val in15 = recent.count { now - it <= 15_000L }
        val next = when {
            worstMs >= 150f -> "SEIZURE"
            in15 >= 3 -> "OSCILLATION"
            else -> "HICCUP"
        }
        val changed = next != state
        state = next
        PerformanceTelemetryRegistry.publishStutter(state, recent.size.toFloat(), worstMs)
        if (changed || now - lastLogMs >= 30_000L) {
            lastLogMs = now
            RuntimeLogger.log("burst " + state + " frames=" + frames +
                " worst=" + String.format("%.0f", worstMs) +
                "ms bursts60s=" + recent.size + " in15s=" + in15, "STUTTER")
        }
    }

    /** decay back to CALM when no bursts for 10s */
    fun startDecay() {
        val t = Thread {
            while (true) {
                try {
                    Thread.sleep(5000)
                    val now = System.currentTimeMillis()
                    synchronized(this) {
                        if (state != "CALM" &&
                            (recent.isEmpty() || now - recent.last() > 10_000L)) {
                            state = "CALM"
                            PerformanceTelemetryRegistry.publishStutter("CALM", 0f, 0f)
                            RuntimeLogger.log("burst CALM restored", "STUTTER")
                        }
                    }
                } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "stutter-decay"; t.start()
    }
}
