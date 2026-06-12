package com.assistant.diagnostic.registry

data class AdapterHealthSnapshot(
    val adapterName: String,
    val status: String,
    val lastHeartbeat: Long,
    val errorCount: Int,
    val recoveryCount: Int,
    val details: String
)

object AdapterHealthRegistry {

    private val snapshots =
        mutableMapOf<String, AdapterHealthSnapshot>()

    @Synchronized
    fun update(snapshot: AdapterHealthSnapshot) {
        snapshots[snapshot.adapterName] = snapshot
    }

    @Synchronized
    fun getAll(): List<AdapterHealthSnapshot> {
        return snapshots.values.toList()
    }

    @Synchronized
    fun get(name: String): AdapterHealthSnapshot? {
        return snapshots[name]
    }
}
