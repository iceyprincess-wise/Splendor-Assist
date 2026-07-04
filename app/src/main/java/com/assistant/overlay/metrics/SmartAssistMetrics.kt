package com.assistant.overlay.metrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class SmartAssistMetricsState(
    val decisionsMade: Long = 0,
    val actionsTriggered: Long = 0,
    val avgDecisionTimeMs: Long = 0,
    val lastDecisionTimeMs: Long = 0,
    val errorCount: Long = 0,
    val uptimeSeconds: Long = 0
)

object SmartAssistMetrics {
    
    private val _state = MutableStateFlow(SmartAssistMetricsState())
    val state: StateFlow<SmartAssistMetricsState> = _state.asStateFlow()
    
    private val decisionsCounter = AtomicLong(0)
    private val actionsCounter = AtomicLong(0)
    private val errorsCounter = AtomicLong(0)
    private val decisionTimeAccumulator = AtomicLong(0)
    private var startTime: Long = System.currentTimeMillis()
    
    fun recordDecision(durationMs: Long, triggeredAction: Boolean) {
        val decisions = decisionsCounter.incrementAndGet()
        val totalTime = decisionTimeAccumulator.addAndGet(durationMs)
        
        if (triggeredAction) {
            actionsCounter.incrementAndGet()
        }
        
        _state.value = SmartAssistMetricsState(
            decisionsMade = decisions,
            actionsTriggered = actionsCounter.get(),
            avgDecisionTimeMs = totalTime / decisions,
            lastDecisionTimeMs = durationMs,
            errorCount = errorsCounter.get(),
            uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
        )
    }
    
    fun recordError() {
        errorsCounter.incrementAndGet()
        _state.value = _state.value.copy(errorCount = errorsCounter.get())
    }
    
    fun reset() {
        decisionsCounter.set(0)
        actionsCounter.set(0)
        errorsCounter.set(0)
        decisionTimeAccumulator.set(0)
        startTime = System.currentTimeMillis()
        _state.value = SmartAssistMetricsState()
    }
    
    fun getSnapshot(): SmartAssistMetricsState = _state.value
}
