package com.assistant.adapter.lmk

object RehydrationRepository {

    private val restored =
        mutableMapOf<String, RehydratedStateSnapshot>()

    @Synchronized
    fun save(snapshot: RehydratedStateSnapshot) {
        restored[snapshot.componentName] = snapshot
    }

    @Synchronized
    fun get(
        componentName: String
    ): RehydratedStateSnapshot? {
        return restored[componentName]
    }

    @Synchronized
    fun getAll(): List<RehydratedStateSnapshot> {
        return restored.values.toList()
    }
}
