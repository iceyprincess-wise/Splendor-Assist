package com.assistant.events

import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/*
 * Task 14 - event bus separation.
 *
 * Three channels with distinct ownership, so a system fault, a runtime gate
 * change, and a gameplay action are never read as the same class of event:
 *
 *   SystemEventHub    - Android/component lifecycle (owned by services)
 *   RuntimeEventHub   - coordinator gates + health (owned by RuntimeCoordinator)
 *   GameplayEventHub  - frame -> decision -> dispatch (owned by the decision
 *                       loop and the dispatch authority: RuntimeDecisionLoop
 *                       emits "routed", GestureExecutionAuthority emits
 *                       "dispatch-accepted" / "dispatch-rejected")
 *
 * Each hub keeps a bounded in-memory ring so diagnostics can read recent
 * history per channel, and still mirrors to RuntimeLogger so existing field
 * logs and forensic files keep working unchanged.
 */

enum class EventChannel { SYSTEM, RUNTIME, GAMEPLAY }

data class RuntimeEvent(
    val channel: EventChannel,
    val name: String,
    val detail: String,
    val timestampMs: Long
)

private const val MAX_HISTORY = 120

abstract class EventHub(
    private val channel: EventChannel,
    private val logTag: String
) {
    private val history = ConcurrentLinkedDeque<RuntimeEvent>()
    private val emitted = AtomicLong(0L)

    @Volatile private var lastName: String = "none"
    @Volatile private var lastUpdatedMs: Long = 0L

    fun emit(name: String, detail: String = "") {
        val event = RuntimeEvent(
            channel = channel,
            name = name,
            detail = detail,
            timestampMs = System.currentTimeMillis()
        )

        // D29-1: the state commit is one atomic step under the hub monitor,
        // so a concurrent reset() or channelRuntimeSnapshot() can never see
        // a half-updated hub (history cleared but emitted still counting,
        // or lastName from one event with lastUpdatedMs from another).
        // The RuntimeLogger mirror runs AFTER the commit and OUTSIDE the
        // lock, so no lock is held across logging and no new lock order
        // is introduced (RuntimeLogger never calls back into a hub).
        synchronized(this) {
            history.addLast(event)
            while (history.size > MAX_HISTORY) {
                history.pollFirst()
            }

            emitted.incrementAndGet()
            lastName = name
            lastUpdatedMs = event.timestampMs
        }

        try {
            RuntimeLogger.log(
                if (detail.isBlank()) name else "$name :: $detail",
                logTag
            )
        } catch (_: Throwable) {
        }
    }

    fun recent(limit: Int = 20): List<RuntimeEvent> =
        // D29-2: takeLast(negative) throws IllegalArgumentException
        // (verified against kotlin-stdlib 1.9.22); clamp so a bad limit
        // yields an empty list instead of crashing a diagnostics caller.
        // D29-1: read under the hub monitor, so the list is one instant.
        synchronized(this) { history.toList().takeLast(limit.coerceAtLeast(0)) }

    fun reset() {
        // D29-1: clear + counters + last-event fields as one atomic step;
        // a concurrent emit can no longer land between history.clear()
        // and emitted.set(0L) and be silently erased from the counters.
        synchronized(this) {
            history.clear()
            emitted.set(0L)
            lastName = "none"
            lastUpdatedMs = 0L
        }
    }

    fun channelRuntimeSnapshot(): Map<String, Any> = synchronized(this) {
        mapOf(
            "channel" to channel.name,
            "emitted" to emitted.get(),
            "lastName" to lastName,
            "lastUpdatedMs" to lastUpdatedMs,
            "history" to history.size
        )
    }
}

object SystemEventHub : EventHub(EventChannel.SYSTEM, "SYSTEM_EVENT")
object RuntimeEventHub : EventHub(EventChannel.RUNTIME, "RUNTIME_EVENT")
object GameplayEventHub : EventHub(EventChannel.GAMEPLAY, "GAMEPLAY_EVENT")

object EventHubs {
    fun resetAll() {
        SystemEventHub.reset()
        RuntimeEventHub.reset()
        GameplayEventHub.reset()
    }

    fun eventRuntimeSnapshot(): Map<String, Any> = mapOf(
        "system" to SystemEventHub.channelRuntimeSnapshot(),
        "runtime" to RuntimeEventHub.channelRuntimeSnapshot(),
        "gameplay" to GameplayEventHub.channelRuntimeSnapshot()
    )
}
