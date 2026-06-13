package com.assistant.adapter.interruption

object NotificationAttenuationEngine {

    fun attenuationLevel(): String {

        val state =
            InterruptionRepository.get()
                ?: return "NORMAL"

        return when (state.severity) {
            "CRITICAL" -> "MAXIMUM"
            "THROTTLE" -> "HIGH"
            "WARNING" -> "MEDIUM"
            else -> "NORMAL"
        }
    }

    fun vibrationAttenuated(): Boolean =
        attenuationLevel() != "NORMAL"

    fun soundAttenuated(): Boolean =
        attenuationLevel() != "NORMAL"
}
