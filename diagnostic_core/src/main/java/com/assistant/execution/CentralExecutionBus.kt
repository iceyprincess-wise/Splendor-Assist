package com.assistant.execution

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
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
    private val queue = ConcurrentLinkedQueue<ExecutionRequest>()
    private val accepted = AtomicLong(0)
    private val consumed = AtomicLong(0)
    private val _statistics = MutableStateFlow(BusStatistics())
    val statistics: StateFlow<BusStatistics> = _statistics.asStateFlow()
    
    private var isRunning = false
    
    fun submit(request: ExecutionRequest): Boolean {
        if (!isRunning) return false
        return try {
            queue.offer(request)
            accepted.incrementAndGet()
            updateStatistics()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun consume(): ExecutionRequest? {
        val request = queue.poll()
        if (request != null) {
            consumed.incrementAndGet()
            updateStatistics()
        }
        return request
    }
    
    fun start() {
        isRunning = true
        updateStatistics()
    }
    
    fun stop() {
        isRunning = false
        updateStatistics()
    }
    
    private fun updateStatistics() {
        _statistics.value = BusStatistics(
            acceptedCount = accepted.get(),
            consumedCount = consumed.get(),
            pendingCount = queue.size,
            isRunning = isRunning
        )
    }
    
    fun acceptedCount(): Long = accepted.get()
    fun consumedCount(): Long = consumed.get()
    fun pendingCount(): Int = queue.size
}
