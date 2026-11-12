package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object Phase3WorldStateStore {

    @Volatile
    private var latest = Phase3WorldState()

    fun update(state: Phase3WorldState) {
        latest = state
    }

    fun current(
): Phase3WorldState =
        latest
}

/* ========
PlayerDetectionResult
======== */
