package com.assistant.overlay.repository

object GoalkeeperRuntimeState {
    @Volatile var enabled = true
    @Volatile var aggressiveMode = false
    @Volatile var positioning = 50
    @Volatile var reactions = 50

    fun sync(
        enabledValue: Boolean,
        aggressiveValue: Boolean,
        positioningValue: Int,
        reactionsValue: Int
    ) {
        enabled = enabledValue
        aggressiveMode = aggressiveValue
        positioning = positioningValue
        reactions = reactionsValue
    }
}
