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
            } catch (_: Throwable) {
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
    fun healthPercent(name: String): Int {

        val snapshot =
            snapshots[name] ?: return 0

        val age =
            System.currentTimeMillis() -
            snapshot.lastHeartbeat

        return when {
            age < 30000 -> 100
            age < 120000 -> 75
            else -> 25
        }
    }

    @Synchronized
    fun effectiveStatus(name: String): String {

        val snapshot =
            snapshots[name] ?: return "OFFLINE"

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
