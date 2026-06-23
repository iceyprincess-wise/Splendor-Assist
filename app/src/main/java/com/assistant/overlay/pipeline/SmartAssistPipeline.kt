package com.assistant.overlay.pipeline

import com.assistant.overlay.repository.SmartAssistState

data class VectorDecision(
    val shouldAct: Boolean,
    val priority: Int,
    val actionType: ActionType,
    val confidence: Float
)

enum class ActionType {
    PASS,
    SHOT,
    CROSS,
    NONE
}

class SmartAssistPipeline {
    
    fun computeVector(
        gameState: GameStateSnapshot,
        assistState: SmartAssistState
    ): VectorDecision {
        
        if (!assistState.enabled) {
            return VectorDecision(false, 0, ActionType.NONE, 0f)
        }
        
        val panicMultiplier = if (assistState.panicMode) 1.5f else 1.0f
        
        val passScore = evaluatePassOpportunity(gameState) * (assistState.passThreshold / 100f) * panicMultiplier
        val shotScore = evaluateShotOpportunity(gameState) * (assistState.shotThreshold / 100f) * panicMultiplier
        val crossScore = evaluateCrossOpportunity(gameState) * (assistState.crossThreshold / 100f) * panicMultiplier
        
        return when {
            shotScore >= shotScore && shotScore >= crossScore && shotScore > 0.3f -> 
                VectorDecision(true, (shotScore * 100).toInt(), ActionType.SHOT, shotScore)
            passScore >= crossScore && passScore > 0.3f -> 
                VectorDecision(true, (passScore * 100).toInt(), ActionType.PASS, passScore)
            crossScore > 0.3f -> 
                VectorDecision(true, (crossScore * 100).toInt(), ActionType.CROSS, crossScore)
            else -> 
                VectorDecision(false, 0, ActionType.NONE, 0f)
        }
    }
    
    private fun evaluatePassOpportunity(state: GameStateSnapshot): Float {
        return if (state.hasOpenTeammate) 0.8f else 0.2f
    }
    
    private fun evaluateShotOpportunity(state: GameStateSnapshot): Float {
        return if (state.isInShootingPosition) 0.9f else 0.1f
    }
    
    private fun evaluateCrossOpportunity(state: GameStateSnapshot): Float {
        return if (state.isNearWing && state.hasTargetInBox) 0.7f else 0.2f
    }
}

data class GameStateSnapshot(
    val hasOpenTeammate: Boolean = false,
    val isInShootingPosition: Boolean = false,
    val isNearWing: Boolean = false,
    val hasTargetInBox: Boolean = false,
    val playerVelocity: Float = 0f,
    val opponentProximity: Float = Float.MAX_VALUE
)
