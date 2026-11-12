package com.assistant.survival

/*
 * Fleet adapter counts by health: ACTIVE / DEGRADED / OFFLINE.
 * Renamed from ResourceBudgetRegistry (patch 3): the registry never
 * tracked resources or budgets - it holds three adapter-health counts
 * written by WatchdogAdapterService and read by DashboardInjector.
 * Counts are fleet-wide: active is the number of adapters whose merged
 * live snapshot reports ACTIVE, not a derivation from the 2-entry
 * restart map (see WatchdogAdapterService).
 */
object AdapterHealthCounts {

    @Volatile
    private var activeAdapters = 0

    @Volatile
    private var degradedAdapters = 0

    @Volatile
    private var offlineAdapters = 0

    @Synchronized
    fun update(
        active: Int,
        degraded: Int,
        offline: Int
    ) {
        activeAdapters = active
        degradedAdapters = degraded
        offlineAdapters = offline
    }

    @Synchronized
    fun snapshot(): String {

        return buildString {

            append("Active : ")
            append(activeAdapters)

            append("\nDegraded : ")
            append(degradedAdapters)

            append("\nOffline : ")
            append(offlineAdapters)
        }
    }
}
