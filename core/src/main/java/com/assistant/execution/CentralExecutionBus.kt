package com.assistant.execution

// F49_PATCH_V1.execution

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
    var timestamp: Long = System.currentTimeMillis(),
    val actionId: String = "ACT-${System.currentTimeMillis()}-${java.util.concurrent.ThreadLocalRandom.current().nextInt(1000, 9999)}",
    var busAccepted: Boolean = false,
    var busConsumed: Boolean = false
)

data class BusStatistics(
    val acceptedCount: Long = 0,
    val consumedCount: Long = 0,
    val pendingCount: Int = 0,
    val isRunning: Boolean = false
)

object CentralExecutionBus {

    // Disruptor Pattern: 3 Rings for Priority Levels (High=100, Med=90, Low=80)
    // Capacity must be power of 2 for fast bitwise AND masking
    private const val RING_CAPACITY = 1024L
    private const val MASK = RING_CAPACITY - 1L

    private class LockFreeRing {
        val buffer = AtomicReferenceArray<ExecutionRequest>(RING_CAPACITY.toInt())
        val writeCursor = AtomicLong(0)
        val readCursor = AtomicLong(0)
    }

    private val highRing = LockFreeRing() // GK, Intercept
    private val medRing = LockFreeRing()  // SmartAssist
    private val lowRing = LockFreeRing()  // Stutter

    private val accepted = AtomicLong(0L)
    private val consumed = AtomicLong(0L)
    private val stoppedDrops = AtomicLong(0L)
    private val invalidDrops = AtomicLong(0L)
    private val staleDrops = AtomicLong(0L)
    private val capacityDrops = AtomicLong(0L)
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

        discardStaleHeads(highRing, System.currentTimeMillis())
        discardStaleHeads(medRing, System.currentTimeMillis())
        discardStaleHeads(lowRing, System.currentTimeMillis())
        val key = request.source to request.phase
        if (reservations.containsKey(key)) return false
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

    private fun consumeFrom(ring: LockFreeRing): ExecutionRequest? {
        while (true) {
            val readPos = ring.readCursor.get()
            val writePos = ring.writeCursor.get()
            if (readPos >= writePos) return null // Empty

            val index = (readPos and MASK).toInt()
            val request = ring.buffer.get(index)
            if (request == null) return null // In-flight write, do not skip slot

            if (ring.readCursor.compareAndSet(readPos, readPos + 1)) {
                ring.buffer.compareAndSet(index, request, null)
                if (requestIsStale(request, System.currentTimeMillis())) {
                    complete(request.actionId)
                    staleDrops.incrementAndGet()
                    continue // Drop stale and try next
                }
                consumed.incrementAndGet()
                request.busConsumed = true
                updateStatistics()
                val point = request.aimMotion?.endpoint(request.startX, request.startY, System.nanoTime())
                return if (point == null) request else request.copy(endX = point.first, endY = point.second)
            }
        }
    }

    private fun discardStaleHeads(ring: LockFreeRing, now: Long) {
        while (ring.readCursor.get() < ring.writeCursor.get()) {
            val index = (ring.readCursor.get() and MASK).toInt()
            val request = ring.buffer.get(index) ?: break
            if (!requestIsStale(request, now)) break
            ring.buffer.set(index, null)
            ring.readCursor.incrementAndGet()
            complete(request.actionId)
            staleDrops.incrementAndGet()
        }
    }

    @Synchronized fun peekSource(): ExecutionSource? {
        val now = System.currentTimeMillis()
        discardStaleHeads(highRing, now)
        discardStaleHeads(medRing, now)
        discardStaleHeads(lowRing, now)
        // Check High
        val hRead = highRing.readCursor.get()
        if (hRead < highRing.writeCursor.get()) {
            val req = highRing.buffer.get((hRead and MASK).toInt())
            if (req != null && !requestIsStale(req, now)) return req.source
        }
        // Check Med
        val mRead = medRing.readCursor.get()
        if (mRead < medRing.writeCursor.get()) {
            val req = medRing.buffer.get((mRead and MASK).toInt())
            if (req != null && !requestIsStale(req, now)) return req.source
        }
        // Check Low
        val lRead = lowRing.readCursor.get()
        if (lRead < lowRing.writeCursor.get()) {
            val req = lowRing.buffer.get((lRead and MASK).toInt())
            if (req != null && !requestIsStale(req, now)) return req.source
        }
        return null
    }

    fun start() { running.set(true); updateStatistics(force = true) }
    @Synchronized fun stop() {
        running.set(false)
        for (ring in listOf(highRing, medRing, lowRing)) {
            while (ring.readCursor.get() < ring.writeCursor.get()) {
                ring.buffer.set((ring.readCursor.getAndIncrement() and MASK).toInt(), null)
            }
        }
        reservations.clear()
        updateStatistics(force = true)
    }
    
    @Synchronized fun clearPending() {
        for (ring in listOf(highRing, medRing, lowRing)) {
            while (ring.readCursor.get() < ring.writeCursor.get()) {
                val req = ring.buffer.getAndSet((ring.readCursor.getAndIncrement() and MASK).toInt(), null)
                if (req != null) complete(req.actionId)
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
                    pendingCount = pendingCount(), isRunning = running.get()
                )
            }
        }
    }

    private fun requestIsValid(req: ExecutionRequest): Boolean =
        req.phase >= 0 && req.startX.isFinite() && req.startY.isFinite() && 
        req.endX.isFinite() && req.endY.isFinite() && req.duration > 0L

    private fun requestIsStale(req: ExecutionRequest, now: Long): Boolean {
        val envelope = req.gestureEnvelope
        if (envelope != null && envelope.isExpired(android.os.SystemClock.elapsedRealtime())) return true
        val age = now - req.timestamp
        if (age < 0L) return true
        val maxAge = when (req.source) {
            ExecutionSource.GOALKEEPER -> 300L
            ExecutionSource.INTERCEPTION -> 300L
            ExecutionSource.SMART_ASSIST -> 250L
            ExecutionSource.STUTTER -> 350L
        }
        return age > maxAge || System.nanoTime() - req.createdAtNanos > maxAge * 1_000_000
    }
}
