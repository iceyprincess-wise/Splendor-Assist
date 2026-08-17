package com.assistant.diagnostic

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Canonical player-observed severe-defect signal.
 *
 * PERFORMANCE:
 *   One 🕶️ tap. Broadcasts to the performance/defensive adapter family.
 *
 * GAMEPLAY:
 *   Double 🕶️ tap. Broadcasts to adapter_smartassist/runtime recovery.
 *
 * The bus does not manufacture lag, stutter, ping or frame data.
 * It records the operator assertion and exposes a bounded escalation epoch.
 */
object DefectEscalationBus {

    enum class Mode {
        NONE,
        PERFORMANCE,
        GAMEPLAY
    }

    data class Event(
        val epoch: Long,
        val mode: Mode,
        val source: String,
        val untilMs: Long
    )

    private const val PERFORMANCE_DURATION_MS = 10_000L
    private const val GAMEPLAY_DURATION_MS = 10_000L

    private val epochCounter = AtomicLong(0L)

    @Volatile
    private var current = Event(
        epoch = 0L,
        mode = Mode.NONE,
        source = "",
        untilMs = 0L
    )

    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    @Synchronized
    fun publishPerformance(source: String = "GLASS_SINGLE") {
        publish(Mode.PERFORMANCE, PERFORMANCE_DURATION_MS, source)
    }

    @Synchronized
    fun publishGameplay(source: String = "GLASS_DOUBLE") {
        publish(Mode.GAMEPLAY, GAMEPLAY_DURATION_MS, source)
    }

    @Synchronized
    private fun publish(
        mode: Mode,
        durationMs: Long,
        source: String
    ) {
        val now = System.currentTimeMillis()
        val event = Event(
            epoch = epochCounter.incrementAndGet(),
            mode = mode,
            source = source,
            untilMs = now + durationMs
        )

        current = event

        RuntimeLogger.log(
            "DEFECT ESCALATION epoch=${event.epoch} mode=${event.mode} " +
                "source=${event.source} durationMs=$durationMs",
            "DEFECT_ESCALATION"
        )

        listeners.forEach { listener ->
            try {
                listener(event)
            } catch (t: Throwable) {
                RuntimeLogger.log(
                    "DEFECT ESCALATION listener failure: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                    "DEFECT_ESCALATION"
                )
            }
        }
    }

    fun register(listener: (Event) -> Unit) {
        listeners.add(listener)
    }

    fun unregister(listener: (Event) -> Unit) {
        listeners.remove(listener)
    }

    fun snapshot(): Event = current

    val performanceActive: Boolean
        get() {
            val e = current
            return e.mode == Mode.PERFORMANCE &&
                System.currentTimeMillis() < e.untilMs
        }

    val gameplayActive: Boolean
        get() {
            val e = current
            return e.mode == Mode.GAMEPLAY &&
                System.currentTimeMillis() < e.untilMs
        }

    fun clearExpired() {
        val e = current
        if (e.mode != Mode.NONE && System.currentTimeMillis() >= e.untilMs) {
            synchronized(this) {
                val latest = current
                if (
                    latest.mode != Mode.NONE &&
                    System.currentTimeMillis() >= latest.untilMs
                ) {
                    current = Event(
                        epoch = latest.epoch,
                        mode = Mode.NONE,
                        source = "EXPIRED",
                        untilMs = 0L
                    )
                }
            }
        }
    }
}
