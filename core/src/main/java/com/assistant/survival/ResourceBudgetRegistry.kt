package com.assistant.survival

object ResourceBudgetRegistry {

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
