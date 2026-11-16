package com.assistant.survival

/*
 * Adapter health ledger: adapter name -> last reported status string.
 * The ledger never held process data (no PID, no process name) - it holds
 * per-adapter health statuses written by WatchdogAdapterService and read
 * by DashboardInjector.
 * Expiry truth (B15-D1): WatchdogAdapterService rewrites EVERY entry,
 * including OFFLINE, on every 15s scan, and upstream never drops a dead
 * adapter - so an entry only goes stale if the watchdog itself stops
 * running. Expiry is therefore watchdog-death detection, NOT dead-adapter
 * removal: while the watchdog runs, a dead adapter is reported OFFLINE
 * indefinitely. The expiry path is kept for exactly that case.
 * Times here are wall-clock milliseconds (System.currentTimeMillis()),
 * the convention shared by the whole health-registry family
 * (AdapterHealthRegistry, AdapterHealthCounts) - switching one file
 * alone to a monotonic clock would be inconsistent (B15-D4, accepted).
 * Snapshot order is sorted by adapter name (was ConcurrentHashMap order).
 */
object AdapterStatusRegistry {

    private data class Entry(val status: String, val updatedAtMs: Long)

    private val adapters =
        java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /** Watchdog refresh period is 15s; 120s is 8 missed cycles. */
    const val STALE_AFTER_MS = 120_000L

    @Volatile
    private var lastUpdateMs = 0L

    @Synchronized
    fun update(
        adapter: String,
        state: String
    ) {
        // B15-D5: keep the "name : state" snapshot format uncorruptible -
        // ':' and newlines in a name or state would break the " : "
        // separator and the one-line-per-adapter shape. Live inputs
        // (adapter names, ACTIVE/DEGRADED/OFFLINE/UNKNOWN) contain
        // neither, so this is a no-op for real data.
        val name = adapter.replace(':', ' ').replace('\n', ' ').replace('\r', ' ')
        val cleanState = state.replace(':', ' ').replace('\n', ' ').replace('\r', ' ')
        val now = System.currentTimeMillis()
        adapters[name] = Entry(cleanState, now)
        lastUpdateMs = now
    }

    @Synchronized
    fun snapshot(): String {
        val now = System.currentTimeMillis()
        adapters.entries.removeIf { now - it.value.updatedAtMs > STALE_AFTER_MS }

        if (adapters.isEmpty()) {
            // B15-D2: "ADAPTERS: NONE" means the ledger was NEVER populated.
            // If entries existed and then expired, the watchdog has stopped
            // refreshing - say so, with the age, instead of pretending
            // there was never anything to report.
            if (lastUpdateMs == 0L)
                return "ADAPTERS: NONE\n"

            val ageSec = (now - lastUpdateMs) / 1000
            return "ADAPTERS: STALE — no watchdog refresh for ${ageSec}s\n"
        }

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
