package com.assistant.execution

// F49_PATCH_V1.execution

import com.assistant.diagnostic.RuntimeLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicBoolean

enum class ExecutionSource {
    SMART_ASSIST,
    GOALKEEPER,
    INTERCEPTION,
    STUTTER
}

data class GestureIntentEnvelope(
    val gestureId: Long,
    val createdAtElapsedMs: Long,
    val lastActivityElapsedMs: Long
) {
    fun isExpired(nowElapsedMs: Long): Boolean =
        nowElapsedMs - lastActivityElapsedMs > 200L || nowElapsedMs - createdAtElapsedMs > 500L
}

data class ExecutionRequest(
    val source: ExecutionSource,
    val phase: Int,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long,
    val gestureEnvelope: GestureIntentEnvelope? = null,
    val createdAtNanos: Long = System.nanoTime(),
    val aimMotion: AimMotion? = null,
    val actionClass: com.assistant.runtime.ActionClass? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val actionId: String = "ACT-${java.util.UUID.randomUUID()}",
    var busAccepted: Boolean = false,
    var busConsumed: Boolean = false
)

data class BusStatistics(
    val acceptedCount: Long = 0,
    val consumedCount: Long = 0,
    val pendingCount: Int = 0,
    val isRunning: Boolean = false,
    val stoppedDrops: Long = 0,
    val invalidDrops: Long = 0,
    val staleDrops: Long = 0,
    val capacityDrops: Long = 0,
    val reservationDrops: Long = 0,
    // D-26-04: a null AimMotion endpoint is not a drop - the request still
    // executes, uncompensated. It gets its own counter so invalidDrops
    // keeps meaning "rejected at submit".
    val aimNullCompensations: Long = 0
)

object CentralExecutionBus {

    // 3 rings for priority levels. The 100/90/80 scale lives in
    // HybridExecutionTerminal (PRIORITY_HIGH/MEDIUM/LOW); this comment
    // restates it - do not change the numbers here without changing them
    // there. The bus orders by ring, not by the numbers.
    // NOTE: this is NOT lock-free despite the earlier "Disruptor" label:
    // submit/consume/complete/stop/clearPending/peekSource are all
    // @Synchronized on this object, so the CAS loop below can never
    // contend. The CAS is kept only as a defensive check; do not remove
    // @Synchronized to "make it lock-free" without a full re-audit.
    // Capacity must be power of 2 for fast bitwise AND masking
    private const val RING_CAPACITY = 1024L
    private const val MASK = RING_CAPACITY - 1L

    private class RingBuffer {
        val buffer = AtomicReferenceArray<ExecutionRequest>(RING_CAPACITY.toInt())
        val writeCursor = AtomicLong(0)
        val readCursor = AtomicLong(0)
    }

    private val highRing = RingBuffer() // GK, Intercept
    private val medRing = RingBuffer()  // SmartAssist
    private val lowRing = RingBuffer()  // Stutter

    private val accepted = AtomicLong(0L)
    private val consumed = AtomicLong(0L)
    private val stoppedDrops = AtomicLong(0L)
    private val invalidDrops = AtomicLong(0L)
    private val staleDrops = AtomicLong(0L)
    private val capacityDrops = AtomicLong(0L)
    private val reservationDrops = AtomicLong(0L)
    private val aimNullCompensations = AtomicLong(0L)
    private val running = AtomicBoolean(true)

    private const val STATS_UPDATE_INTERVAL_MS = 100L
    private val lastStatsUpdate = AtomicLong(0L)

    private val _statistics = MutableStateFlow(BusStatistics())
    val statistics: StateFlow<BusStatistics> = _statistics.asStateFlow()

    private val reservations = mutableMapOf<Pair<ExecutionSource, Int>, String>()
    // F38-D2: reservation timestamps, keyed exactly like reservations.
    // The drop log used to name only the rejected request, so a wedged
    // (source, phase) never named its holder. Pruned wherever
    // reservations is pruned (complete/stop).
    private val reservationSinceNanos = mutableMapOf<Pair<ExecutionSource, Int>, Long>()
    // D-26-05: removal is by actionId VALUE, not by (source, phase) key,
    // so it releases every reservation holding that actionId. That is
    // correct only because an actionId is unique per logical action:
    // production requests get a fresh UUID, and .copy() preserves both the
    // actionId AND the source/phase, so a copy shares the same reservation
    // key. No production code sets actionId= explicitly or copies a
    // request across sources/phases; if one ever does, complete() by key
    // is required instead.
    @Synchronized fun complete(actionId: String) {
        val keys = reservations.filterValues { it == actionId }.keys.toList()
        keys.forEach { reservations.remove(it); reservationSinceNanos.remove(it) }
    }

    @Synchronized fun submit(request: ExecutionRequest): Boolean {
        // D-26-06: the gate values are captured once and reused in the log,
        // so the logged values are the ones that took this branch (the old
        // code re-evaluated them and could log different values).
        val runningNow = running.get()
        val uiBlocked = UiExecutionGate.blocked()
        if (!runningNow || uiBlocked) {
            stoppedDrops.incrementAndGet()
            RuntimeLogger.log("CentralExecutionBus submit dropped: stopped running=$runningNow uiBlocked=$uiBlocked source=${request.source} phase=${request.phase} actionId=${request.actionId}", "CentralExecutionBus")
            return false
        }
        val nowNanos = System.nanoTime()
        if (!requestIsValid(request, nowNanos)) {
            invalidDrops.incrementAndGet()
            RuntimeLogger.log("CentralExecutionBus submit dropped: invalid source=${request.source} phase=${request.phase} actionId=${request.actionId}", "CentralExecutionBus")
            return false
        }
        if (requestIsStale(request, nowNanos)) {
            staleDrops.incrementAndGet()
            RuntimeLogger.log("CentralExecutionBus submit dropped: stale source=${request.source} phase=${request.phase} actionId=${request.actionId}", "CentralExecutionBus")
            return false
        }

        discardStaleHeads(highRing)
        discardStaleHeads(medRing)
        discardStaleHeads(lowRing)
        val key = request.source to request.phase
        if (reservations.containsKey(key)) {
            reservationDrops.incrementAndGet()
            // F38-D2: name the holder and its age — the 2026-11-16 live
            // session's GK phase-4 wedge (no dispatch after 21:56:13,
            // drops to 22:39:51) could not be attributed because the
            // drop named only the rejected request.
            val holderActionId = reservations[key]
            val holderAgeMs = (nowNanos - (reservationSinceNanos[key] ?: nowNanos)) / 1_000_000L
            RuntimeLogger.log("CentralExecutionBus submit dropped: reservation source=${request.source} phase=${request.phase} actionId=${request.actionId} holder=$holderActionId holderAgeMs=$holderAgeMs", "CentralExecutionBus")
            return false
        }
        val ring = when (request.source) {
            ExecutionSource.GOALKEEPER, ExecutionSource.INTERCEPTION -> highRing
            ExecutionSource.SMART_ASSIST -> medRing
            ExecutionSource.STUTTER -> lowRing
        }

        while (true) {
            val writePos = ring.writeCursor.get()
            val readPos = ring.readCursor.get()

            if (writePos - readPos >= RING_CAPACITY) {
                capacityDrops.incrementAndGet()
                RuntimeLogger.log("CentralExecutionBus submit dropped: capacity source=${request.source} phase=${request.phase} actionId=${request.actionId}", "CentralExecutionBus")
                return false
            }

            if (ring.writeCursor.compareAndSet(writePos, writePos + 1)) {
                reservations[key] = request.actionId
                reservationSinceNanos[key] = nowNanos
                request.busAccepted = true
                ring.buffer.set((writePos and MASK).toInt(), request)
                accepted.incrementAndGet()
                updateStatistics()
                return true
            }
        }
    }

    @Synchronized fun consume(): ExecutionRequest? {
        // D-26-08: this wipe is defence-in-depth, not load-bearing -
        // UiExecutionGate.block() already wipes the queue on a genuine
        // transition. It is kept so a blocked consume never returns a
        // request even if the block() path changes.
        if (UiExecutionGate.blocked()) { clearPending(); return null }
        // Priority Arbitration: High -> Med -> Low
        consumeFrom(highRing)?.let { return it }
        consumeFrom(medRing)?.let { return it }
        return consumeFrom(lowRing)
    }

    private fun consumeFrom(ring: RingBuffer): ExecutionRequest? {
        while (true) {
            val readPos = ring.readCursor.get()
            val writePos = ring.writeCursor.get()
            if (readPos >= writePos) return null // Empty

            val index = (readPos and MASK).toInt()
            val request = ring.buffer.get(index)
            // Defensive guard only: every bus entry point is @Synchronized,
            // so a slot can never hold a half-written request in practice.
            if (request == null) return null // In-flight write, do not skip slot

            if (ring.readCursor.compareAndSet(readPos, readPos + 1)) {
                ring.buffer.compareAndSet(index, request, null)
                if (requestIsStale(request)) {
                    complete(request.actionId)
                    staleDrops.incrementAndGet()
                    continue // Drop stale and try next
                }
                consumed.incrementAndGet()
                request.busConsumed = true
                updateStatistics()
                val point = request.aimMotion?.endpoint(request.startX, request.startY, System.nanoTime())
                if (point == null && request.aimMotion != null) {
                    // D-26-04: the request still executes, uncompensated -
                    // nothing was dropped, so this is NOT counted in
                    // invalidDrops. It gets its own counter
                    // (aimNullCompensations) and the caller receives the
                    // raw request; there is no flag distinguishing it from
                    // a compensated one, so the log line is the marker.
                    aimNullCompensations.incrementAndGet()
                    RuntimeLogger.log("CentralExecutionBus: AimMotion.endpoint returned null, executing uncompensated actionId=${request.actionId}", "CentralExecutionBus")
                }
                return if (point == null) request else request.copy(endX = point.first, endY = point.second)
            }
        }
    }

    // Head-only by design: a stale entry behind a fresh head keeps its
    // reservation until the head is consumed, so a fresh same-phase submit
    // can stall for at most one queue drain. A full-ring sweep was NOT
    // done: nulling a middle slot would make consumeFrom() return null
    // (it treats null as an in-flight write) and stall the whole ring.
    private fun discardStaleHeads(ring: RingBuffer) {
        while (ring.readCursor.get() < ring.writeCursor.get()) {
            val index = (ring.readCursor.get() and MASK).toInt()
            val request = ring.buffer.get(index) ?: break
            if (!requestIsStale(request)) break
            ring.buffer.set(index, null)
            ring.readCursor.incrementAndGet()
            complete(request.actionId)
            staleDrops.incrementAndGet()
        }
    }

    // D-26-07: this "peek" MUTATES - it discards stale heads on all three
    // rings before reading. The sole caller (SmartAssistAccessibilityEngine
    // busRunnable) uses it only to decide preemption, then calls consume()
    // separately; another producer can submit between the two calls, so the
    // preemption decision can be stale. That is accepted polling design
    // (9ms poll; consumeFrom re-checks staleness) - do not "fix" it by
    // holding the bus lock across both calls from the engine.
    @Synchronized fun peekSource(): ExecutionSource? {
        discardStaleHeads(highRing)
        discardStaleHeads(medRing)
        discardStaleHeads(lowRing)
        // Check High
        val hRead = highRing.readCursor.get()
        if (hRead < highRing.writeCursor.get()) {
            val req = highRing.buffer.get((hRead and MASK).toInt())
            if (req != null && !requestIsStale(req)) return req.source
        }
        // Check Med
        val mRead = medRing.readCursor.get()
        if (mRead < medRing.writeCursor.get()) {
            val req = medRing.buffer.get((mRead and MASK).toInt())
            if (req != null && !requestIsStale(req)) return req.source
        }
        // Check Low
        val lRead = lowRing.readCursor.get()
        if (lRead < lowRing.writeCursor.get()) {
            val req = lowRing.buffer.get((lRead and MASK).toInt())
            if (req != null && !requestIsStale(req)) return req.source
        }
        return null
    }

    // D-26-09: @Synchronized for uniformity with stop() (both touch the
    // same running flag and statistics). The initial running=true (bus
    // accepts submits from class-load, before RuntimeCoordinator G5
    // start()) is kept: the consumer starts at G5, and any pre-G5
    // submission queues in the ring (<=1024) and is consumed at G5 or
    // goes stale - bounded, not a leak.
    @Synchronized fun start() { running.set(true); updateStatistics(force = true) }
    @Synchronized fun stop() {
        running.set(false)
        for (ring in listOf(highRing, medRing, lowRing)) {
            while (ring.readCursor.get() < ring.writeCursor.get()) {
                val req = ring.buffer.getAndSet((ring.readCursor.getAndIncrement() and MASK).toInt(), null)
                if (req != null) stoppedDrops.incrementAndGet()
            }
        }
        reservations.clear()
        reservationSinceNanos.clear()
        updateStatistics(force = true)
    }
    
    @Synchronized fun clearPending() {
        for (ring in listOf(highRing, medRing, lowRing)) {
            while (ring.readCursor.get() < ring.writeCursor.get()) {
                val req = ring.buffer.getAndSet((ring.readCursor.getAndIncrement() and MASK).toInt(), null)
                if (req != null) { complete(req.actionId); stoppedDrops.incrementAndGet() }
            }
        }
        updateStatistics(force = true)
    }

    fun pendingCount(): Int {
        var count = 0
        count += maxOf(0, (highRing.writeCursor.get() - highRing.readCursor.get()).toInt())
        count += maxOf(0, (medRing.writeCursor.get() - medRing.readCursor.get()).toInt())
        count += maxOf(0, (lowRing.writeCursor.get() - lowRing.readCursor.get()).toInt())
        return count
    }

    // D-26-11: the snapshot's pendingCount is throttled telemetry (100ms),
    // not the live count - ContributionRegistry reads statistics.value
    // while RuntimeCoordinator reads pendingCount() live, and the two can
    // disagree by up to one interval. If a consumer needs exactness, read
    // pendingCount() directly.
    private fun updateStatistics(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastStatsUpdate.get()
        if (force || now - last >= STATS_UPDATE_INTERVAL_MS) {
            if (force || lastStatsUpdate.compareAndSet(last, now)) {
                _statistics.value = BusStatistics(
                    acceptedCount = accepted.get(), consumedCount = consumed.get(),
                    pendingCount = pendingCount(), isRunning = running.get(),
                    stoppedDrops = stoppedDrops.get(), invalidDrops = invalidDrops.get(),
                    staleDrops = staleDrops.get(), capacityDrops = capacityDrops.get(),
                    reservationDrops = reservationDrops.get(),
                    aimNullCompensations = aimNullCompensations.get()
                )
            }
        }
    }

    // D-26-10: a request stamped in the future (createdAtNanos ahead of
    // submit time) would never go stale - the age is negative - and would
    // hold its (source, phase) reservation until wall time caught up. No
    // producer stamps the future (construction always precedes submit), so
    // it is rejected here. No upper bounds are imposed on duration or
    // coordinates: producers clamp them (GK coerces to screen bounds,
    // RuntimeDecisionLoop coerces the lead to frame bounds, durations are
    // coerced to 15-85ms / >= MIN_STROKE_DURATION); adding a range here
    // would silently change the accepted range, so the contract is
    // documented, not enforced.
    private fun requestIsValid(req: ExecutionRequest, nowNanos: Long = System.nanoTime()): Boolean =
        req.phase >= 0 && req.startX.isFinite() && req.startY.isFinite() &&
        req.endX.isFinite() && req.endY.isFinite() && req.duration > 0L &&
        req.createdAtNanos <= nowNanos

    // Staleness is monotonic-clock only (System.nanoTime() vs createdAtNanos).
    // The previous wall-clock term (now - timestamp) could stale a fresh
    // request on a clock jump forward, or on a jump backward (age < 0).
    // timestamp is kept as a record field only; it no longer decides
    // staleness anywhere in the repo (ContributionRegistry.drainBest now
    // uses the same budget and clock as this function).
    //
    // Shared staleness budget per source (ms). Single source of truth for
    // requestIsStale() and ContributionRegistry.drainBest() - there is no
    // other copy of these numbers. Do not change one side without the
    // other; drainBest() calls maxAgeMs() directly.
    fun maxAgeMs(source: ExecutionSource): Long = when (source) {
        ExecutionSource.GOALKEEPER -> 300L
        ExecutionSource.INTERCEPTION -> 300L
        ExecutionSource.SMART_ASSIST -> 250L
        ExecutionSource.STUTTER -> 350L
    }
    private fun requestIsStale(req: ExecutionRequest, nowNanos: Long = System.nanoTime()): Boolean {
        val envelope = req.gestureEnvelope
        if (envelope != null && envelope.isExpired(android.os.SystemClock.elapsedRealtime())) return true
        return nowNanos - req.createdAtNanos > maxAgeMs(req.source) * 1_000_000
    }
}
