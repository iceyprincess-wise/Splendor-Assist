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
    val reservationDrops: Long = 0
)

object CentralExecutionBus {

    // 3 rings for priority levels (High=100, Med=90, Low=80).
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
    private val running = AtomicBoolean(true)

    private const val STATS_UPDATE_INTERVAL_MS = 100L
    private val lastStatsUpdate = AtomicLong(0L)

    private val _statistics = MutableStateFlow(BusStatistics())
    val statistics: StateFlow<BusStatistics> = _statistics.asStateFlow()

    private val reservations = mutableMapOf<Pair<ExecutionSource, Int>, String>()
    @Synchronized fun complete(actionId: String) {
        reservations.entries.removeAll { it.value == actionId }
    }

    @Synchronized fun submit(request: ExecutionRequest): Boolean {
        if (!running.get() || UiExecutionGate.blocked()) {
            stoppedDrops.incrementAndGet()
            return false
        }
        if (!requestIsValid(request)) {
            invalidDrops.incrementAndGet()
            return false
        }
        if (requestIsStale(request)) {
            staleDrops.incrementAndGet()
            return false
        }

        discardStaleHeads(highRing)
        discardStaleHeads(medRing)
        discardStaleHeads(lowRing)
        val key = request.source to request.phase
        if (reservations.containsKey(key)) { reservationDrops.incrementAndGet(); return false }
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
                return false
            }

            if (ring.writeCursor.compareAndSet(writePos, writePos + 1)) {
                reservations[key] = request.actionId
                request.busAccepted = true
                ring.buffer.set((writePos and MASK).toInt(), request)
                accepted.incrementAndGet()
                updateStatistics()
                return true
            }
        }
    }

    @Synchronized fun consume(): ExecutionRequest? {
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
                    invalidDrops.incrementAndGet()
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

    fun start() { running.set(true); updateStatistics(force = true) }
    @Synchronized fun stop() {
        running.set(false)
        for (ring in listOf(highRing, medRing, lowRing)) {
            while (ring.readCursor.get() < ring.writeCursor.get()) {
                val req = ring.buffer.getAndSet((ring.readCursor.getAndIncrement() and MASK).toInt(), null)
                if (req != null) stoppedDrops.incrementAndGet()
            }
        }
        reservations.clear()
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
                    reservationDrops = reservationDrops.get()
                )
            }
        }
    }

    private fun requestIsValid(req: ExecutionRequest): Boolean =
        req.phase >= 0 && req.startX.isFinite() && req.startY.isFinite() && 
        req.endX.isFinite() && req.endY.isFinite() && req.duration > 0L

    // Staleness is monotonic-clock only (System.nanoTime() vs createdAtNanos).
    // The previous wall-clock term (now - timestamp) could stale a fresh
    // request on a clock jump forward, or on a jump backward (age < 0).
    // timestamp is kept as a record field only; it no longer decides staleness.
    private fun requestIsStale(req: ExecutionRequest): Boolean {
        val envelope = req.gestureEnvelope
        if (envelope != null && envelope.isExpired(android.os.SystemClock.elapsedRealtime())) return true
        val maxAge = when (req.source) {
            ExecutionSource.GOALKEEPER -> 300L
            ExecutionSource.INTERCEPTION -> 300L
            ExecutionSource.SMART_ASSIST -> 250L
            ExecutionSource.STUTTER -> 350L
        }
        return System.nanoTime() - req.createdAtNanos > maxAge * 1_000_000
    }
}
