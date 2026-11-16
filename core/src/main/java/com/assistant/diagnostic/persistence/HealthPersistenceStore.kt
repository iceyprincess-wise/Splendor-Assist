package com.assistant.diagnostic.persistence

import android.content.Context
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import org.json.JSONObject
import java.io.File

object HealthPersistenceStore {

    private const val FILE_NAME = "adapter_health.json"

    private const val TEMP_SUFFIX = ".tmp"

    private fun file(context: Context): File {
        // H32-D6: getExternalFilesDir() returns null when external storage
        // is not currently mounted; File(null, ...) throws NPE. Fall back
        // to the always-available internal files dir. Both readers and
        // writers go through this one helper, so they always agree on
        // which file is live (mount state is device-global).
        val dir =
            context.getExternalFilesDir(null) ?: context.filesDir

        return File(dir, FILE_NAME)
    }

    private val lastWriteMs = HashMap<String, Long>()
    private val lastWriteStatus = HashMap<String, String>()
    private const val WRITE_MIN_INTERVAL_MS = 5000L

    /*
     * H32-D2: atomic replace. File.writeText() truncates then writes, so
     * a main-process readAll() running concurrently (or a process kill
     * mid-write) can observe an empty / half-written file. Writing a
     * temp file in the same directory and renaming over the store makes
     * the swap atomic (rename(2) on one filesystem): readers always see
     * the old complete file or the new complete file, never a torn one.
     * A stale .tmp left behind by a crash is harmless: it is overwritten
     * on the next write and never read. Returns false when the rename
     * itself fails, so the caller can fall back to a direct write rather
     * than losing the update entirely.
     */
    private fun writeAtomically(
        store: File,
        text: String
    ): Boolean {
        return try {
            val parent =
                store.parentFile ?: return false

            val tmp =
                File(parent, FILE_NAME + TEMP_SUFFIX)

            tmp.writeText(text)

            tmp.renameTo(store)
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun write(
        context: Context,
        snapshot: AdapterHealthSnapshot
    ) {
        val now = System.currentTimeMillis()
        val prevMs = lastWriteMs[snapshot.adapterName] ?: 0L
        if (lastWriteStatus[snapshot.adapterName] == snapshot.status &&
            now - prevMs < WRITE_MIN_INTERVAL_MS) return
        try {

            val store =
                file(context)

            val root =
                if (store.exists()) {
                    try {
                        JSONObject(store.readText())
                    } catch (e: Exception) {
                        // H32-D1: a torn / corrupt store used to abort
                        // the whole write here, and the file stayed
                        // corrupt forever, so EVERY later write failed
                        // the same way (permanent brick: cross-process
                        // health went dark until uninstall / manual
                        // delete, and the watchdog saw missing snapshots
                        // as UNKNOWN and never restarted dead engines).
                        // Start from a fresh object instead: the update
                        // lands, and the corrupt payload is replaced by
                        // the next atomic write below.
                        com.assistant.diagnostic.RuntimeLogger.log("HealthPersistence corrupt store, starting fresh: " + e, "HEALTH_IO")
                        JSONObject()
                    }
                } else {
                    JSONObject()
                }

            root.put(
                snapshot.adapterName,
                JSONObject().apply {

                    put(
                        "adapterName",
                        snapshot.adapterName
                    )

                    put(
                        "status",
                        snapshot.status
                    )

                    put(
                        "lastHeartbeat",
                        snapshot.lastHeartbeat
                    )

                    put(
                        "errorCount",
                        snapshot.errorCount
                    )

                    put(
                        "recoveryCount",
                        snapshot.recoveryCount
                    )

                    put(
                        "details",
                        snapshot.details
                    )
                }
            )

            if (!writeAtomically(store, root.toString())) {
                store.writeText(
                    root.toString()
                )
            }

            // H32-D4: record the throttle state only once the write
            // actually landed. It used to be recorded before the try,
            // so a failed write (disk error, corrupt read, null
            // external dir) still counted as written and suppressed
            // same-status retries for 5s.
            lastWriteMs[snapshot.adapterName] = now
            lastWriteStatus[snapshot.adapterName] = snapshot.status

        } catch (e: Exception) {
            com.assistant.diagnostic.RuntimeLogger.log("HealthPersistence write failed: " + e, "HEALTH_IO")
        }
    }

    @Synchronized
    fun readAll(
        context: Context
    ): List<AdapterHealthSnapshot> {

        return try {

            val store =
                file(context)

            if (!store.exists())
                return emptyList()

            val root =
                JSONObject(
                    store.readText()
                )

            buildList {

                val keys =
                    root.keys()

                while (keys.hasNext()) {

                    val key =
                        keys.next()

                    // H32-D3: one unreadable entry used to throw out of
                    // getJSONObject() and drop the whole list. Skip the
                    // bad entry and keep the good ones.
                    try {
                        val obj =
                            root.getJSONObject(key)

                        add(
                            AdapterHealthSnapshot(
                                // H32-D5: the JSON key IS the adapter
                                // name; fall back to it when the stored
                                // adapterName field is missing / blank
                                // instead of merging a ""-named snapshot.
                                adapterName =
                                    obj.optString("adapterName").ifBlank { key },

                                status =
                                    obj.optString("status"),

                                lastHeartbeat =
                                    obj.optLong("lastHeartbeat"),

                                errorCount =
                                    obj.optInt("errorCount"),

                                recoveryCount =
                                    obj.optInt("recoveryCount"),

                                details =
                                    obj.optString("details")
                            )
                        )
                    } catch (e: Exception) {
                        com.assistant.diagnostic.RuntimeLogger.log("HealthPersistence skipping unreadable entry: " + key + ": " + e, "HEALTH_IO")
                    }
                }
            }

        } catch (e: Exception) {

            // H32-D3: readAll used to swallow failures silently, so a
            // corrupt file was indistinguishable from no file at every
            // caller (getAllLive's own try/catch + log around readAll
            // can never fire: readAll never throws). Log it here.
            com.assistant.diagnostic.RuntimeLogger.log("HealthPersistence read failed: " + e, "HEALTH_IO")

            emptyList()
        }
    }
}
