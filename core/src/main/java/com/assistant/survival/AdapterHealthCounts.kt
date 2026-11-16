package com.assistant.survival

/*
 * Fleet adapter counts by health: ACTIVE / DEGRADED / OFFLINE.
 * Renamed from ResourceBudgetRegistry (patch 3): the registry never
 * tracked resources or budgets - it holds three adapter-health counts
 * written by WatchdogAdapterService and read by DashboardInjector.
 * Counts are fleet-wide: ACTIVE is the number of adapters whose freshest
 * merged heartbeat is <30s old (AdapterHealthRegistry.effectiveStatus:
 * age < 30000 -> ACTIVE, < 120000 -> DEGRADED, else OFFLINE), not a
 * derivation from the 2-entry restart map (see WatchdogAdapterService).
 * UNKNOWN adapters (no snapshot at all) appear in the AdapterStatusRegistry
 * ledger but are NOT counted here: active + degraded + offline sum to the
 * liveSnapshots.size the watchdog scanned, and an UNKNOWN adapter
 * contributes to none of the three counts - there is deliberately no
 * 4th count (that would need a 4th update() parameter and a
 * WatchdogAdapterService change, out of scope for this round).
 * Counts never expire on their own: snapshot() reports NO DATA YET before
 * the first update, and STALE once the last update is older than
 * STALE_AFTER_MS (120s, the same bound as the sibling registry, 8 missed
 * 15s watchdog scans). The counts and the ledger are not read atomically,
 * so the dashboard can briefly show a mismatched pair - a combined
 * snapshot API plus a DashboardInjector change would be needed to fix
 * that, and neither is in this round (AHC-D2, open).
 */
object AdapterHealthCounts {

    /** Watchdog refresh period is 15s; 120s is 8 missed cycles. */
    const val STALE_AFTER_MS = 120_000L

    @Volatile
    private var activeAdapters = 0

    @Volatile
    private var degradedAdapters = 0

    @Volatile
    private var offlineAdapters = 0

    @Volatile
    private var updatedAtMs = 0L

    @Synchronized
    fun update(
        active: Int,
        degraded: Int,
        offline: Int
    ) {
        // AHC-D5: counts are sizes, never negative. The sole writer
        // (WatchdogAdapterService) only increments from 0, so this cannot
        // trip on the live path - it guards against a future caller
        // passing a computed (possibly negative) value.
        require(active >= 0 && degraded >= 0 && offline >= 0) {
            "AdapterHealthCounts must not be negative: active=$active degraded=$degraded offline=$offline"
        }
        activeAdapters = active
        degradedAdapters = degraded
        offlineAdapters = offline
        updatedAtMs = System.currentTimeMillis()
    }

    @Synchronized
    fun snapshot(): String {
        val now = System.currentTimeMillis()
        if (updatedAtMs == 0L)
            return "Counts: NO DATA YET"

        return buildString {
            if (now - updatedAtMs > STALE_AFTER_MS) {
                append("Counts: STALE — no watchdog refresh for ")
                append((now - updatedAtMs) / 1000)
                append("s\n")
            }

            append("Active : ")
            append(activeAdapters)

            append("\nDegraded : ")
            append(degradedAdapters)

            append("\nOffline : ")
            append(offlineAdapters)
        }
    }
}
