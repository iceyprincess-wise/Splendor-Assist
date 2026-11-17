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
        // No63-D14: positioning/reactions were stored verbatim — a caller
        // could store garbage (negative, >100) that every reader then had
        // to coerce again. The runtime state is the single choke point
        // (GoalkeeperRepository.save() flows through here), so clamp once,
        // here.
        positioning = positioningValue.coerceIn(0, 100)
        reactions = reactionsValue.coerceIn(0, 100)
    }

    // No63-D15: test hook (no53 precedent — singletons get a reset()).
    // Restores the defaults; no production caller.
    fun reset() {
        enabled = true
        aggressiveMode = false
        positioning = 50
        reactions = 50
    }
}
