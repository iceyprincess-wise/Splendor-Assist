package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object GameStateBuilder {

    @Volatile
    private var latest = GameStateSnapshot()

    fun update(
        snapshot: GameStateSnapshot
    ) {
        latest = snapshot
    }

    fun current(): GameStateSnapshot {
        return latest
    }
}

/* ========
GameStateFusion
======== */
