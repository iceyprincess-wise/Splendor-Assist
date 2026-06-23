package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.NativePipelineCache

import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource

data class VectorDecision(
    val shouldAct: Boolean,
    val priority: Int,
    val actionType: ActionType,
    val confidence: Float,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long
)

enum class ActionType {
    PASS,
    SHOT,
    CROSS,
    NONE
}

class SmartAssistPipeline {
    
    fun computeOptimalVector(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ): VectorDecision {
        NativePipelineCache.cacheNode(0,startX,startY);
        NativePipelineCache.cacheNode(1,endX,endY);

        val deltaX = endX - startX
        val deltaY = endY - startY
        val distance = kotlin.math.hypot(deltaX, deltaY)
        
        return VectorDecision(
            shouldAct = distance > 10,
            priority = (distance / 10).toInt().coerceAtMost(100),
            actionType = ActionType.PASS,
            confidence =
            (
                0.8f +
                ((distance/1000f).coerceAtMost(0.2f))
            ).coerceAtMost(1f),
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration
        )
    }
    
    fun createExecutionRequest(decision: VectorDecision): ExecutionRequest? {
        if (!decision.shouldAct) return null
        
        return ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = 1,
            startX = decision.startX,
            startY = decision.startY,
            endX = decision.endX,
            endY = decision.endY,
            duration = decision.duration
        )
    }
}
