package com.assistant.overlay.analytics

import com.assistant.overlay.metrics.SmartAssistMetrics
import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry

object LiveMatchAnalytics{

    fun possession():Float{

        val s=
            SmartAssistMetrics.getSnapshot()

        return (
            s.actionsTriggered
                .coerceAtMost(100)
        ).toFloat()
    }

    fun passing():Float{

        val s=
            SmartAssistMetrics.getSnapshot()

        return (
            s.decisionsMade
                .coerceAtMost(100)
        ).toFloat()
    }

    fun interceptions():Int=
        InterceptionRuntimeRegistry.prediction

    fun transition():Long=
        SmartAssistMetrics
            .getSnapshot()
            .avgDecisionTimeMs
}
