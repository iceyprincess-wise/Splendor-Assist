package com.assistant.adapter.smartassist

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
