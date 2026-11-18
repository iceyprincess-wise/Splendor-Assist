package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object TelemetryRepository {

    @Volatile
    private var snapshot = TelemetrySnapshot()

    // All writes serialize on this object's monitor, so a transform
    // read-modify-write cannot interleave with a whole-snapshot write.
    @Synchronized
    fun update(newSnapshot: TelemetrySnapshot) {
        snapshot = newSnapshot
    }

    // No68-E2-D5: atomic read-modify-write. TelemetryCoordinator used to
    // do current() + copy() + update() as three separate steps, so a
    // ball write racing a goalkeeper write silently dropped the other
    // updater's fields. Updaters must use this overload.
    @Synchronized
    fun update(transform: (TelemetrySnapshot) -> TelemetrySnapshot) {
        snapshot = transform(snapshot)
    }

    // No68-E2-D10: the repository had no reset — a stale snapshot
    // survived RuntimeCoordinator.reset() into the next epoch (same
    // class as No66-D40). The call-site wiring is QE2-3.
    @Synchronized
    fun reset() {
        snapshot = TelemetrySnapshot()
    }

    fun current(): TelemetrySnapshot {
        return snapshot
    }
}

/* ========
TemporalMemoryState
======== */
