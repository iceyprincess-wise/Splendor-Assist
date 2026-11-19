package com.assistant.survival

/*
 * AHC-D2 (batch 5): the single shared lock for the two adapter-health
 * registries (AdapterStatusRegistry ledger + AdapterHealthCounts counts).
 * Both registries used to lock on their own monitor, so the dashboard
 * could read a ledger from one watchdog scan and counts from the next —
 * a briefly mismatched pair. update()/snapshot() in both files now
 * synchronize on this one lock, and combinedSnapshot() reads both under
 * it with a single shared `now`, so the pair is always one generation.
 * The lock lives in its own file so neither registry depends on the
 * other for it.
 */
internal object AdapterHealthLock {
    val lock = Any()
}
