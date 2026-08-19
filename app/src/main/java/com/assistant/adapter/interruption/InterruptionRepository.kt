package com.assistant.adapter.interruption

object InterruptionRepository {

    @Volatile
    private var latest: InterruptionState? = null

    fun save(state: InterruptionState) {
        latest = state
    }

    fun get(): InterruptionState? {
        return latest
    }
}
