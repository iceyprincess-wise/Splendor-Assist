package com.assistant.overlay.interceptor

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.execution.ContributionRegistry

enum class AnticipationResult {
    TRACK,
    SAVE,
    INTERCEPT,
    PANIC
}

object ShotAnticipationEngine {

    // Legacy fallback version to guarantee compilation compatibility with your older analysis registries
    fun evaluate(decision: ThreatDecision): AnticipationResult {
        val predictionBonus = InterceptionRuntimeRegistry.prediction + GoalkeeperAdaptiveFeedbackEngine.interceptionBonus()
        return when {
            decision.direction == ShotDirection.CROSS && predictionBonus >= 70 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.LONG_BALL && predictionBonus >= 70 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.CROSS && decision.priority >= 90 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.LONG_BALL && decision.priority >= 85 -> AnticipationResult.INTERCEPT
            decision.priority >= 130 -> AnticipationResult.PANIC
            decision.priority >= 100 -> AnticipationResult.SAVE
            decision.priority >= 75 -> AnticipationResult.INTERCEPT
            else -> AnticipationResult.TRACK
        }
    }
}
