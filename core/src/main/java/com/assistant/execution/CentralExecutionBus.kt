package com.assistant.execution

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

data class ExecutionRequest(
    val source: ExecutionSource,
    val phase: Int,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long,
    val timestamp: Long = System.currentTimeMillis()
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

    private val _statistics = MutableStateFlow(BusStatistics())
    val statistics: StateFlow<BusStatistics> = _statistics.asStateFlow()

    fun submit(request: ExecutionRequest): Boolean {
        if (!running.get()) {
            stoppedDrops.incrementAndGet()
            return false
        }
        if (!requestIsValid(request)) {
            invalidDrops.incrementAndGet()
            return false
        }

        val ring = when (request.source) {
            ExecutionSource.GOALKEEPER, ExecutionSource.INTERCEPTION -> highRing
            ExecutionSource.SMART_ASSIST -> medRing
            ExecutionSource.STUTTER -> lowRing
        }

        val writePos = ring.writeCursor.getAndIncrement()
        val readPos = ring.readCursor.get()

        if (writePos - readPos >= RING_CAPACITY) {
            ring.writeCursor.decrementAndGet() // Rollback
            capacityDrops.incrementAndGet()
            return false
        }

        ring.buffer.set((writePos and MASK).toInt(), request)
        accepted.incrementAndGet()
        updateStatistics()
        return true
    }

    fun consume(): ExecutionRequest? {
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

            val request = ring.buffer.get((readPos and MASK).toInt())
            if (ring.readCursor.compareAndSet(readPos, readPos + 1)) {
                if (request == null) continue // Race condition, slot empty
                if (requestIsStale(request, System.currentTimeMillis())) {
                    staleDrops.incrementAndGet()
                    continue // Drop stale and try next
                }
                consumed.incrementAndGet()
                return request
            }
        }
    }

    fun peekSource(): ExecutionSource? {
        // Check High
        val hRead = highRing.readCursor.get()
        if (hRead < highRing.writeCursor.get()) {
            val req = highRing.buffer.get((hRead and MASK).toInt())
            if (req != null) return req.source
        }
        // Check Med
        val mRead = medRing.readCursor.get()
        if (mRead < medRing.writeCursor.get()) {
            val req = medRing.buffer.get((mRead and MASK).toInt())
            if (req != null) return req.source
        }
        // Check Low
        val lRead = lowRing.readCursor.get()
        if (lRead < lowRing.writeCursor.get()) {
            val req = lowRing.buffer.get((lRead and MASK).toInt())
            if (req != null) return req.source
        }
        return null
    }

    fun start() { running.set(true); updateStatistics() }
    fun stop() { running.set(false); updateStatistics() }
    fun acceptedCount(): Long = accepted.get()
    fun consumedCount(): Long = consumed.get()
    
    fun pendingCount(): Int {
        var count = 0
        count += (highRing.writeCursor.get() - highRing.readCursor.get()).toInt()
        count += (medRing.writeCursor.get() - medRing.readCursor.get()).toInt()
        count += (lowRing.writeCursor.get() - lowRing.readCursor.get()).toInt()
        return count
    }

    fun dropStatistics() = mapOf(
        "stopped" to stoppedDrops.get(), "invalid" to invalidDrops.get(),
        "stale" to staleDrops.get(), "capacity" to capacityDrops.get()
    )

    private fun updateStatistics() {
        _statistics.value = BusStatistics(
            acceptedCount = accepted.get(), consumedCount = consumed.get(),
            pendingCount = pendingCount(), isRunning = running.get()
        )
    }

    private fun requestIsValid(req: ExecutionRequest): Boolean =
        req.phase >= 0 && req.startX.isFinite() && req.startY.isFinite() && 
        req.endX.isFinite() && req.endY.isFinite() && req.duration > 0L

    private fun requestIsStale(req: ExecutionRequest, now: Long): Boolean {
        val age = now - req.timestamp
        if (age < 0L) return false
        val maxAge = when (req.source) {
            ExecutionSource.GOALKEEPER -> 300L
            ExecutionSource.INTERCEPTION -> 300L
            ExecutionSource.SMART_ASSIST -> 250L
            ExecutionSource.STUTTER -> 350L
        }
        return age > maxAge
    }
}
