package com.assistant.survival

/*
 * Adapter health ledger: adapter name -> last reported status string.
 * Renamed from ProcessSurvivalRegistry (patch 3): the ledger never held
 * process data (no PID, no process name) - it holds per-adapter health
 * statuses written by WatchdogAdapterService and read by DashboardInjector.
 * Entries expire: a status not refreshed within STALE_AFTER_MS is dropped
 * from the snapshot so a dead adapter stops being reported forever.
 * Snapshot order is sorted by adapter name (was ConcurrentHashMap order).
 */
object AdapterStatusRegistry {

    private data class Entry(val status: String, val updatedAtMs: Long)

    private val adapters =
        java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /** Watchdog refresh period is 15s; 120s is 8 missed cycles. */
    const val STALE_AFTER_MS = 120_000L

    @Synchronized
    fun update(
        adapter: String,
        state: String
    ) {
        adapters[adapter] = Entry(state, System.currentTimeMillis())
    }

    @Synchronized
    fun snapshot(): String {
        val now = System.currentTimeMillis()
        adapters.entries.removeIf { now - it.value.updatedAtMs > STALE_AFTER_MS }

        if (adapters.isEmpty())
            return "ADAPTERS: NONE"

        return buildString {

            append("ADAPTERS\n")

            adapters.entries.sortedBy { it.key }.forEach { (name, entry) ->

                append(name)
                append(" : ")
                append(entry.status)
                append("\n")
            }
        }
    }
}
