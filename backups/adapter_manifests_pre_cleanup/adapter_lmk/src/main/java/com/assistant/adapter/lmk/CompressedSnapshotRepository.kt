package com.assistant.adapter.lmk

object CompressedSnapshotRepository {

    private val snapshots =
        mutableMapOf<String, CompressedSnapshot>()

    @Synchronized
    fun save(snapshot: CompressedSnapshot) {
        snapshots[snapshot.componentName] = snapshot
    }

    @Synchronized
    fun get(component: String): CompressedSnapshot? {
        return snapshots[component]
    }

    @Synchronized
    fun getAll(): List<CompressedSnapshot> {
        return snapshots.values.toList()
    }
}
