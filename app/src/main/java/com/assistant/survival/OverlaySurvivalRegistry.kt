package com.assistant.survival

object OverlaySurvivalRegistry {

    @Volatile
    private var attached = false

    @Volatile
    private var lastSeen = 0L

    @Synchronized
    fun update(
        isAttached: Boolean
    ) {

        attached = isAttached

        lastSeen =
            System.currentTimeMillis()
    }

    @Synchronized
    fun state(): String {

        val age =
            System.currentTimeMillis() -
            lastSeen

        return when {

            !attached ->
                "OFFLINE"

            age > 120000 ->
                "DEGRADED"

            else ->
                "ACTIVE"
        }
    }
}
