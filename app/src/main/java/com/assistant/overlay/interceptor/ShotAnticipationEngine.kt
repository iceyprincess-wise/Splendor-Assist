package com.assistant.overlay.interceptor

enum class AnticipationResult {
    TRACK,
    SAVE,
    INTERCEPT,
    PANIC
}

object ShotAnticipationEngine {

    fun evaluate(
        decision: ThreatDecision
    ): AnticipationResult {

        return when {

            decision.priority >= 130 ->
                AnticipationResult.PANIC

            decision.priority >= 100 ->
                AnticipationResult.SAVE

            decision.priority >= 75 ->
                AnticipationResult.INTERCEPT

            else ->
                AnticipationResult.TRACK
        }
    }
}
