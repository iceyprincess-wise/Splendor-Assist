package com.assistant.survival

object AccessibilitySurvivalRegistry {

    @Volatile
    private var state = "UNKNOWN"

    @Volatile
    private var lastEvent = 0L

    @Synchronized
    fun update(
        status: String
    ) {
        state = status
        lastEvent =
            System.currentTimeMillis()
    }

    @Synchronized
    fun state(): String {

        val age =
            System.currentTimeMillis() -
            lastEvent

        return when {

            age < 30000 ->
                state

            age < 120000 ->
                "DEGRADED"

            else ->
                "OFFLINE"
        }
    }
}
