package com.assistant.diagnostic.persistence

import android.content.Context
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import org.json.JSONObject
import java.io.File

object HealthPersistenceStore {

    private const val FILE_NAME = "adapter_health.json"

    private fun file(context: Context): File =
        File(context.getExternalFilesDir(null), FILE_NAME)

    @Synchronized
    fun write(
        context: Context,
        snapshot: AdapterHealthSnapshot
    ) {
        try {

            val store =
                file(context)

            val root =
                if (store.exists())
                    JSONObject(store.readText())
                else
                    JSONObject()

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

            store.writeText(
                root.toString()
            )

        } catch (_: Exception) {
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

                    val obj =
                        root.getJSONObject(key)

                    add(
                        AdapterHealthSnapshot(
                            adapterName =
                                obj.optString("adapterName"),

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
                }
            }

        } catch (_: Exception) {

            emptyList()
        }
    }
}
