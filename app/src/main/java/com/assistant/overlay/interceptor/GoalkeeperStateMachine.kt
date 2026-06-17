package com.assistant.overlay.interceptor

enum class GoalkeeperState {
    IDLE,
    TRACKING,
    THREAT,
    SAVE,
    PANIC,
    CROSS,
    RECOVERY
}

object GoalkeeperStateMachine {

    @Volatile
    private var current =
        GoalkeeperState.IDLE

    fun state(): GoalkeeperState =
        current

    fun transition(
        next: GoalkeeperState
    ) {
        current = next
    }
}
