package com.assistant.execution

import com.assistant.diagnostic.RuntimeLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ExecutionSource {
    SMART_ASSIST,
    GOALKEEPER,
    INTERCEPTION,
    STUTTER,
    FUTURE_ENGINE
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

    private const val MAX_PENDING_REQUESTS = 64

    private data class QueuedRequest(
        val request: ExecutionRequest,
        val sequence: Long
    )

    data class DropStatistics(
        val stopped: Long,
        val invalid: Long,
        val stale: Long,
        val superseded: Long,
        val capacity: Long
    )

    private val queueComparator =
        Comparator<QueuedRequest> { left, right ->
            val priorityComparison =
                HybridExecutionTerminal
                    .priority(right.request.source)
                    .compareTo(
                        HybridExecutionTerminal.priority(
                            left.request.source
                        )
                    )

            if (priorityComparison != 0) {
                priorityComparison
            } else {
                val timestampComparison =
                    left.request.timestamp.compareTo(
                        right.request.timestamp
                    )

                if (timestampComparison != 0) {
                    timestampComparison
                } else {
                    left.sequence.compareTo(right.sequence)
                }
            }
        }

    private val queue =
        PriorityBlockingQueue(
            11,
            queueComparator
        )

    private val accepted = AtomicLong(0L)
    private val consumed = AtomicLong(0L)
    private val submissionSequence = AtomicLong(0L)

    private val stoppedDrops = AtomicLong(0L)
    private val invalidDrops = AtomicLong(0L)
    private val staleDrops = AtomicLong(0L)
    private val supersededDrops = AtomicLong(0L)
    private val capacityDrops = AtomicLong(0L)

    private val running = AtomicBoolean(true)
    private val mutationLock = Any()

    private val _statistics =
        MutableStateFlow(BusStatistics())

    val statistics: StateFlow<BusStatistics> =
        _statistics.asStateFlow()

    fun submit(request: ExecutionRequest): Boolean {
        if (!running.get()) {
            stoppedDrops.incrementAndGet()
            updateStatistics()
            return false
        }

        if (!requestIsValid(request)) {
            invalidDrops.incrementAndGet()
            updateStatistics()
            return false
        }

        val now = System.currentTimeMillis()
        if (requestIsStale(request, now)) {
            staleDrops.incrementAndGet()
            updateStatistics()
            return false
        }

        synchronized(mutationLock) {
            if (!running.get()) {
                stoppedDrops.incrementAndGet()
                updateStatisticsLocked()
                return false
            }

            removeSupersededLocked(request)

            if (queue.size >= MAX_PENDING_REQUESTS) {
                capacityDrops.incrementAndGet()
                updateStatisticsLocked()
                return false
            }

            val queued =
                QueuedRequest(
                    request = request,
                    sequence = submissionSequence.incrementAndGet()
                )

            val offered = queue.offer(queued)
            if (!offered) {
                capacityDrops.incrementAndGet()
                updateStatisticsLocked()
                return false
            }

            accepted.incrementAndGet()
            updateStatisticsLocked()
        }

        RuntimeLogger.execution(
            "BUS_SUBMIT",
            "source=${request.source} phase=${request.phase}"
        )
        return true
    }

    fun consume(): ExecutionRequest? {
        while (true) {
            val queued =
                synchronized(mutationLock) {
                    val next = queue.poll()
                    updateStatisticsLocked()
                    next
                } ?: return null

            val request = queued.request
            if (requestIsStale(request, System.currentTimeMillis())) {
                staleDrops.incrementAndGet()
                updateStatistics()
                continue
            }

            consumed.incrementAndGet()
            updateStatistics()

            RuntimeLogger.execution(
                "BUS_CONSUME",
                "source=${request.source} phase=${request.phase}"
            )
            return request
        }
    }

    /*
     * Non-destructive look at the highest-priority fresh request. Purges
     * stale corpses first (attributed to staleDrops exactly like consume()).
     *
     * This exists for the dispatcher's preemption decision: while a gesture
     * is in flight, the dispatcher needs to know whether something MORE
     * important than the in-flight action is waiting - without consuming
     * it prematurely. Returns only the source; the request itself stays
     * queued until consume().
     */
    fun peekSource(): ExecutionSource? =
        synchronized(mutationLock) {
            purgeStaleLocked()
            queue.peek()?.request?.source
        }

    fun start() {
        running.set(true)
        updateStatistics()
    }

    fun stop() {
        synchronized(mutationLock) {
            running.set(false)

            val discarded = queue.size
            if (discarded > 0) {
                queue.clear()
                staleDrops.addAndGet(discarded.toLong())
            }

            updateStatisticsLocked()
        }
    }

    fun acceptedCount(): Long = accepted.get()

    fun consumedCount(): Long = consumed.get()

    /*
     * Truthful pending count. Requests carry hard per-source lifetimes
     * (120-750ms); anything older is already undeliverable - consume()
     * would discard it on sight. Leaving corpses in the queue poisoned
     * this reading (the idle "busPending = 2..4" reports): they counted
     * as pending while being nothing but stale bodies awaiting a consumer
     * that had no reason to run. Purge them here, attributed to staleDrops
     * exactly as consume() would have done.
     */
    fun pendingCount(): Int =
        synchronized(mutationLock) {
            purgeStaleLocked()
            queue.size
        }

    fun dropStatistics(): DropStatistics =
        DropStatistics(
            stopped = stoppedDrops.get(),
            invalid = invalidDrops.get(),
            stale = staleDrops.get(),
            superseded = supersededDrops.get(),
            capacity = capacityDrops.get()
        )

    private fun purgeStaleLocked() {
        val now = System.currentTimeMillis()
        var dropped = 0L
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (requestIsStale(iterator.next().request, now)) {
                iterator.remove()
                dropped++
            }
        }
        if (dropped > 0L) {
            staleDrops.addAndGet(dropped)
            updateStatisticsLocked()
        }
    }

    private fun requestIsValid(
        request: ExecutionRequest
    ): Boolean =
        request.phase >= 0 &&
            request.startX.isFinite() &&
            request.startY.isFinite() &&
            request.endX.isFinite() &&
            request.endY.isFinite() &&
            request.startX >= 0.0f &&
            request.startY >= 0.0f &&
            request.endX >= 0.0f &&
            request.endY >= 0.0f &&
            request.duration > 0L &&
            request.timestamp > 0L

    private fun requestIsStale(
        request: ExecutionRequest,
        now: Long
    ): Boolean {
        val age = now - request.timestamp

        if (age < 0L) {
            return false
        }

        return age > maximumAgeMs(request.source)
    }

    private fun maximumAgeMs(
        source: ExecutionSource
    ): Long =
        when (source) {
            ExecutionSource.GOALKEEPER -> 120L
            ExecutionSource.INTERCEPTION -> 180L
            ExecutionSource.SMART_ASSIST -> 300L
            ExecutionSource.STUTTER -> 500L
            ExecutionSource.FUTURE_ENGINE -> 750L
        }

    private fun removeSupersededLocked(
        incoming: ExecutionRequest
    ) {
        val removed =
            queue.removeIf { queued ->
                queued.request.source == incoming.source &&
                    queued.request.phase == incoming.phase
            }

        if (removed) {
            supersededDrops.incrementAndGet()
        }
    }

    private fun updateStatistics() {
        synchronized(mutationLock) {
            updateStatisticsLocked()
        }
    }

    private fun updateStatisticsLocked() {
        _statistics.value =
            BusStatistics(
                acceptedCount = accepted.get(),
                consumedCount = consumed.get(),
                pendingCount = queue.size,
                isRunning = running.get()
            )
    }
}
