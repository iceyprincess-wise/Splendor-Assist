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

    @Synchronized
    fun healthPercent(name: String): Int {
        val snapshot = snapshots[name] ?: return 0
        val age = System.currentTimeMillis() - snapshot.lastHeartbeat

        return when {
            age < 30000 -> 100
            age < 120000 -> 75
            else -> 25
        }
    }

    @Synchronized
    fun effectiveStatus(name: String): String {
        val snapshot = snapshots[name] ?: return "OFFLINE"
        val age = System.currentTimeMillis() - snapshot.lastHeartbeat

        return when {
            age < 30000 -> "ACTIVE"
            age < 120000 -> "DEGRADED"
            else -> "OFFLINE"
        }
    }
}
