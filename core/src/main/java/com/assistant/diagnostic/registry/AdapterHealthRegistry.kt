package com.assistant.diagnostic.registry

import android.content.Context
import com.assistant.diagnostic.persistence.HealthPersistenceStore

data class AdapterHealthSnapshot(
    val adapterName: String,
    val status: String,
    val lastHeartbeat: Long,
    val errorCount: Int,
    val recoveryCount: Int,
    val details: String
)

object AdapterHealthRegistry {

    @Volatile
    private var applicationContext: Context? = null

    private val snapshots =
        mutableMapOf<String, AdapterHealthSnapshot>()

    fun initialize(
        context: Context
    ) {
        applicationContext =
            context.applicationContext
    }

    @Synchronized
    fun update(snapshot: AdapterHealthSnapshot) {

        snapshots[snapshot.adapterName] =
            snapshot

        applicationContext?.let {
            HealthPersistenceStore.write(
                it,
                snapshot
            )
        }
    }

    @Synchronized
    fun restore(
        persisted: List<AdapterHealthSnapshot>
    ) {
        persisted.forEach {
            snapshots[it.adapterName] = it
        }
    }

    @Synchronized
    fun getAll(): List<AdapterHealthSnapshot> {
        return snapshots.values.toList()
    }

    /*
     * Cross-process truth. The adapters (net / lag / stutter ...) heartbeat
     * from their OWN processes: their updates land in their process's
     * in-memory map and in the shared persistence file - never in this
     * process's map. Anything that gates on adapter health from the main
     * runtime (booster gate, health monitor) must therefore merge the
     * persisted snapshots with the local ones, preferring the freshest
     * heartbeat per adapter.
     */
    @Synchronized
    fun getAllLive(): List<AdapterHealthSnapshot> {
        val merged = HashMap<String, AdapterHealthSnapshot>()

        applicationContext?.let { ctx ->
            try {
                HealthPersistenceStore.readAll(ctx).forEach {
                    merged[it.adapterName] = it
                }
            } catch (t: Throwable) {
                com.assistant.diagnostic.RuntimeLogger.log("getAllLive: persisted health snapshots unreadable: " + t.message, "ADAPTER_HEALTH")
            }
        }

        snapshots.values.forEach { local ->
            val existing = merged[local.adapterName]
            if (existing == null || local.lastHeartbeat > existing.lastHeartbeat) {
                merged[local.adapterName] = local
            }
        }

        return merged.values.toList()
    }

    @Synchronized
    fun get(name: String): AdapterHealthSnapshot? {
        return snapshots[name]
    }

    @Synchronized
    fun effectiveStatus(name: String): String {

        // Cross-process truth: consult the merged live list (persisted +
        // local, freshest heartbeat wins), not only this process's map.
        // A snapshot that exists only in another process's persistence
        // file used to read as OFFLINE here.
        val snapshot =
            getAllLive().firstOrNull { it.adapterName == name } ?: return "OFFLINE"

        return effectiveStatus(snapshot)
    }

    /*
     * Pure snapshot form: no map lookup, no persistence read. Callers
     * that already hold a merged live snapshot (WatchdogAdapterService)
     * must use this so one scan does not re-read the persistence file
     * once per adapter.
     */
    fun effectiveStatus(snapshot: AdapterHealthSnapshot): String {
        val age =
            System.currentTimeMillis() -
            snapshot.lastHeartbeat

        return when {
            age < 30000 -> "ACTIVE"
            age < 120000 -> "DEGRADED"
            else -> "OFFLINE"
        }
    }
}
