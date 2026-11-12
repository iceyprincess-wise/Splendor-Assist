package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object TelemetryRepository {

    @Volatile
    private var snapshot = TelemetrySnapshot()

    fun update(newSnapshot: TelemetrySnapshot) {
        snapshot = newSnapshot
    }

    fun current(): TelemetrySnapshot {
        return snapshot
    }
}

/* ========
TemporalMemoryState
======== */
