package com.assistant.adapter.interruption

object CounterThrottleEngine {

    fun recommendedMode(): String {

        val state =
            InterruptionRepository.get()
                ?: return "NORMAL"

        return when (state.severity) {
            "CRITICAL" -> "AGGRESSIVE_THROTTLE"
            "THROTTLE" -> "MODERATE_THROTTLE"
            "WARNING" -> "LIGHT_THROTTLE"
            else -> "NORMAL"
        }
    }
}
