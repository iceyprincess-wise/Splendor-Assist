package com.assistant.overlay.interceptor

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource

enum class AnticipationResult {
    TRACK,
    SAVE,
    INTERCEPT,
    PANIC
}

object ShotAnticipationEngine {

    // Upgraded main evaluation routine that takes physical screen anchor coordinates
    fun evaluateAndDispatch(
        decision: ThreatDecision,
        anchorX: Float = 825f,  // Default standard horizontal center fallback
        anchorY: Float = 360f   // Default standard vertical center fallback
    ): AnticipationResult {

        // 1. Run your core evaluation matrix algorithms intact
        val predictionBonus = InterceptionRuntimeRegistry.prediction +
                GoalkeeperAdaptiveFeedbackEngine.interceptionBonus()

        val result = when {
            decision.direction == ShotDirection.CROSS && predictionBonus >= 70 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.LONG_BALL && predictionBonus >= 70 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.CROSS && decision.priority >= 90 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.LONG_BALL && decision.priority >= 85 -> AnticipationResult.INTERCEPT
            decision.priority >= 130 -> AnticipationResult.PANIC
            decision.priority >= 100 -> AnticipationResult.SAVE
            decision.priority >= 75 -> AnticipationResult.INTERCEPT
            else -> AnticipationResult.TRACK
        }

        // 2. UPGRADE: If a high-tier danger state is evaluated, immediately dispatch an automated bus stroke
        if (result == AnticipationResult.SAVE || result == AnticipationResult.INTERCEPT || result == AnticipationResult.PANIC) {
            try {
                // Calculate an interceptive layout direction based on threat priority weight
                val sweepMagnitude = 60f
                val endYOffset = if (result == AnticipationResult.SAVE) -sweepMagnitude else sweepMagnitude

                val request = ExecutionRequest(
                    source = ExecutionSource.INTERCEPTION,
                    phase = 5,
                    startX = anchorX,
                    startY = anchorY,
                    endX = anchorX,
                    endY = anchorY + endYOffset,
                    duration = 30L // Fast-executing 30ms stroke path to defeat low-end budget input latency
                )

                val submitted = CentralExecutionBus.submit(request)
                if (submitted) {
                    RuntimeLogger.log("SHOT_ANTICIPATION automated defensive bus dispatch triggered action=$result", "DEFENSE")
                }
            } catch (e: Exception) {
                RuntimeLogger.log("Defensive hardware dispatch step skipped: ${e.message}", "DEFENSE")
            }
        }

        return result
    }

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
