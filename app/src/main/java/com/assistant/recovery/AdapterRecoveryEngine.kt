package com.assistant.recovery

import android.content.Context
import android.os.Handler
import android.os.Looper

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry

object AdapterRecoveryEngine {

    private val handler =
        Handler(Looper.getMainLooper())

    private var started = false

    private val runnable =
        object : Runnable {

            override fun run() {

                try {

                    AdapterHealthRegistry
                        .getAll()
                        .forEach { snapshot ->

                            val status =
                                AdapterHealthRegistry
                                    .effectiveStatus(
                                        snapshot.adapterName
                                    )

                            if (
                                status == "OFFLINE"
                            ) {

                                RuntimeLogger.log(
                                    "Recovery requested :: ${snapshot.adapterName}",
                                    "RECOVERY"
                                )
                            }
                        }

                } catch (_: Exception) {
                }

                handler.postDelayed(
                    this,
                    30000L
                )
            }
        }

    fun start(
        context: Context
    ) {

        if (started)
            return

        started = true

        handler.post(
            runnable
        )

        RuntimeLogger.log(
            "Recovery engine started",
            "RECOVERY"
        )
    }
}
