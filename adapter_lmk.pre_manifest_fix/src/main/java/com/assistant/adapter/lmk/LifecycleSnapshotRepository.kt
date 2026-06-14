package com.assistant.adapter.lmk

object LifecycleSnapshotRepository {

    private val snapshots =
        mutableMapOf<String, StateSnapshot>()

    @Synchronized
    fun save(snapshot: StateSnapshot) {
        snapshots[snapshot.componentName] = snapshot
    }

    @Synchronized
    fun get(component: String): StateSnapshot? {
        return snapshots[component]
    }

    @Synchronized
    fun getAll(): List<StateSnapshot> {
        return snapshots.values.toList()
    }
}
