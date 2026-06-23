package com.assistant.overlay.bus

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

data class ExecutionRequest(
    val source: String,
    val phase: ExecutionPhase,
    val coordinates: Pair<Float, Float>? = null,
    val duration: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Map<String, Any> = emptyMap()
)

enum class ExecutionPhase {
    DETECT,
    DECIDE,
    ACT,
    COMPLETE,
    ERROR
}

data class BusStatistics(
    val acceptedCount: Long = 0,
    val consumedCount: Long = 0,
    val rejectedCount: Long = 0,
    val queueDepth: Int = 0,
    val isRunning: Boolean = false
)

class CentralExecutionBus(private val scope: CoroutineScope) {
    
    companion object {
        const val SOURCE_SMART_ASSIST = "smart_assist"
        const val SOURCE_GOALKEEPER = "goalkeeper"
        const val SOURCE_INTERCEPTION = "interception"
        const val SOURCE_THERMAL = "thermal"
        const val SOURCE_MEMORY = "memory"
    }
    
    private val requestQueue = ConcurrentLinkedQueue<ExecutionRequest>()
    private val _statistics = MutableStateFlow(BusStatistics())
    val statistics: StateFlow<BusStatistics> = _statistics.asStateFlow()
    
    private val acceptedCounter = AtomicLong(0)
    private val consumedCounter = AtomicLong(0)
    private val rejectedCounter = AtomicLong(0)
    
    private var busJob: Job? = null
    private var isRunning = false
    
    private val processors = mutableMapOf<String, suspend (ExecutionRequest) -> Boolean>()
    
    fun registerProcessor(source: String, processor: suspend (ExecutionRequest) -> Boolean) {
        processors[source] = processor
    }
    
    fun unregisterProcessor(source: String) {
        processors.remove(source)
    }
    
    fun submit(request: ExecutionRequest): Boolean {
        if (!isRunning) return false
        
        return try {
            requestQueue.offer(request)
            acceptedCounter.incrementAndGet()
            updateStatistics()
            true
        } catch (e: Exception) {
            rejectedCounter.incrementAndGet()
            updateStatistics()
            false
        }
    }
    
    fun start() {
        if (isRunning) return
        
        isRunning = true
        busJob = scope.launch(Dispatchers.Default) {
            while (isActive && isRunning) {
                processNextRequest()
                delay(16) // ~60fps processing
            }
        }
        updateStatistics()
    }
    
    fun stop() {
        isRunning = false
        busJob?.cancel()
        busJob = null
        updateStatistics()
    }
    
    private suspend fun processNextRequest() {
        val request = requestQueue.poll() ?: return
        
        val processor = processors[request.source]
        val success = try {
            processor?.invoke(request) ?: false
        } catch (e: Exception) {
            false
        }
        
        if (success) {
            consumedCounter.incrementAndGet()
        }
        updateStatistics()
    }
    
    private fun updateStatistics() {
        _statistics.value = BusStatistics(
            acceptedCount = acceptedCounter.get(),
            consumedCount = consumedCounter.get(),
            rejectedCount = rejectedCounter.get(),
            queueDepth = requestQueue.size,
            isRunning = isRunning
        )
    }
    
    fun getQueueSnapshot(): List<ExecutionRequest> {
        return requestQueue.toList()
    }
    
    fun clearQueue() {
        requestQueue.clear()
        updateStatistics()
    }
}
