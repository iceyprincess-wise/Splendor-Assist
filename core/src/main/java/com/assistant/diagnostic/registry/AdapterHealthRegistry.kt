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
        val appContext =
            context.applicationContext
        applicationContext =
            appContext
        // D31-1: restore() had no callers, so the local map started empty
        // in every process and the local readers read empty until this
        // process's own adapters heartbeated - and the main process never
        // heartbeats at all (all three update() writers run in
        // :survival), so its local list was permanently empty. Seed the
        // map from the persisted file at startup so the local readers
        // report the last-known fleet state. restore() merges
        // freshest-wins, so a re-initialise can never regress live
        // entries. readAll() itself swallows file errors (empty list),
        // the catch here covers only Errors escaping it.
        try {
            restore(
                HealthPersistenceStore.readAll(
                    appContext
                )
            )
        } catch (t: Throwable) {
            com.assistant.diagnostic.RuntimeLogger.log("initialize: persisted health snapshots unrestorable: " + t.message, "ADAPTER_HEALTH")
        }
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
        // D31-2: freshest heartbeat wins, the same rule as getAllLive().
        // The old blind overwrite could regress a live local snapshot
        // with a stale persisted one if restore() ever ran after
        // heartbeats had arrived; with the D31-1 initialise wiring that
        // is now a real path, so the merge must be safe to call twice.
        persisted.forEach {
            val existing = snapshots[it.adapterName]
            if (existing == null || it.lastHeartbeat > existing.lastHeartbeat) {
                snapshots[it.adapterName] = it
            }
        }
    }

    /*
     * Cross-process truth. The three heartbeat writers
     * (gameplay_engine, performance_engine, adapter_watchdog) all run in
     * the :survival process, while the main process never heartbeats: a
     * main-process reader's in-memory map therefore holds only what
     * initialize() restored from the shared persistence file. Anything
     * that gates on adapter health from the main runtime (booster gate,
     * health monitor) must therefore merge the persisted snapshots with
     * the local ones, preferring the freshest heartbeat per adapter.
     * (D31-3: the old comment claimed the adapters heartbeat "from
     * their OWN processes" and named net / lag / stutter adapters -
     * neither is true. All three writers share :survival per the
     * manifest, and no net/lag/stutter adapter ever calls update();
     * the cross-process split that matters is main vs :survival.)
     * Item 41 (letter A): the local-only getAll() and get(name) are
     * deleted — zero callers, and this function subsumes them.
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
