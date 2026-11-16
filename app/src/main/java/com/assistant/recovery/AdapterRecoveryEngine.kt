package com.assistant.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

import com.assistant.diagnostic.RuntimeLogger

import com.assistant.diagnostic.registry.AdapterHealthRegistry

/*
 * D31-5: this engine was dead - start() had no callers repo-wide, so no
 * recovery scan ever ran. It is NOT superseded: WatchdogAdapterService
 * (the :survival watchdog) restarts only performance_engine and
 * gameplay_engine, every 15s with no cooldown; it cannot restart itself,
 * and nothing else re-launches it after death - IgnitionEngine.ignite()
 * is a one-shot at boot. This engine is the only mechanism that can
 * restart adapter_watchdog, and it must run in the MAIN process to do
 * so (started from App.onCreate's isMainProcess branch): a :survival
 * copy would die with the watchdog it is meant to revive. It
 * complements the watchdog rather than replacing it - one launch
 * attempt per outage episode (120s cooldown + activeLaunches guard),
 * recovery verification, and RecoveryMetricsRegistry telemetry - and
 * launching an already-running service is a harmless onStartCommand
 * re-delivery, so the two scanners cannot fight each other.
 */
object AdapterRecoveryEngine {

    private val handler =
        Handler(Looper.getMainLooper())

    private var started = false

    private var appContext: Context? = null

    private val cooldowns =
        mutableMapOf<String, Long>()

    private val pendingRecovery =
        mutableSetOf<String>()

    private val activeLaunches =
        mutableSetOf<String>()

    private const val COOLDOWN_MS =
        120000L

        private val adapterMap =
        mapOf(
            "performance_engine" to
                "com.assistant.PerformanceEngineService",

            "adapter_watchdog" to
                "com.assistant.adapter.watchdog.WatchdogAdapterService",

            "gameplay_engine" to
                "com.assistant.GameplayEngineService"
        )

    private fun launchAdapter(
        context: Context,
        adapterName: String
    ) {

        synchronized(activeLaunches) {

            if (
                activeLaunches.contains(
                    adapterName
                )
            ) {
                return
            }

            activeLaunches.add(
                adapterName
            )
        }

        val className =
            adapterMap[adapterName]
                ?: return

        val intent =
            Intent().apply {

                component =
                    ComponentName(
                        context.packageName,
                        className
                    )
            }

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {
                context.startForegroundService(
                    intent
                )
            } else {
                context.startService(
                    intent
                )
            }

            RecoveryMetricsRegistry
                .recordAttempt()

            RuntimeLogger.log(
                "Recovery requested :: $adapterName",
                "RECOVERY"
            )

        } catch (e: Exception) {

            RuntimeLogger.log(
                "Recovery failed :: $adapterName :: ${e.javaClass.simpleName}",
                "RECOVERY"
            )

            synchronized(activeLaunches) {
                activeLaunches.remove(
                    adapterName
                )
            }
        }
    }

    private val runnable =
        object : Runnable {

            override fun run() {

                try {

                    val context =
                        appContext

                    if (context != null) {

                        var offline = 0

                        // D31-5: getAllLive() is the cross-process truth
                        // (persisted + local, freshest heartbeat wins).
                        // getAll() saw only this process's map, which in
                        // the main process holds nothing until the
                        // registry is seeded - cross-process OFFLINE
                        // adapters were invisible here. The pure
                        // effectiveStatus(snapshot) form computes the same
                        // thresholds (30s/120s) from the snapshot already
                        // in hand, instead of re-reading the persistence
                        // file once per adapter per scan via the name form.
                        AdapterHealthRegistry
                            .getAllLive()
                            .forEach { snapshot ->

                                val status =
                                    AdapterHealthRegistry
                                        .effectiveStatus(
                                            snapshot
                                        )

                                if (
                                    status == "OFFLINE"
                                ) {

                                    offline++

                                    val now =
                                        System.currentTimeMillis()

                                    val last =
                                        cooldowns[
                                            snapshot.adapterName
                                        ] ?: 0L

                                    if (
                                        now - last >=
                                        COOLDOWN_MS
                                    ) {

                                        cooldowns[
                                            snapshot.adapterName
                                        ] = now

                                        pendingRecovery.add(
                                            snapshot.adapterName
                                        )

                                        launchAdapter(
                                            context,
                                            snapshot.adapterName
                                        )
                                    }
                                } else {

                                    if (
                                        pendingRecovery.contains(
                                            snapshot.adapterName
                                        )
                                    ) {

                                        pendingRecovery.remove(
                                            snapshot.adapterName
                                        )

                                        RecoveryMetricsRegistry
                                            .recordSuccess()

                                        synchronized(activeLaunches) {
                                            activeLaunches.remove(
                                                snapshot.adapterName
                                            )
                                        }

                                        RuntimeLogger.log(
                                            "Recovery verified :: ${snapshot.adapterName}",
                                            "RECOVERY"
                                        )
                                    }
                                }
                            }

                        RecoveryMetricsRegistry
                            .setOfflineAdapters(
                                offline
                            )
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

        appContext =
            context.applicationContext

        // D31-5: the scan reads getAllLive(), which merges the persisted
        // file only when the registry was initialised in this process.
        // BoosterIgnition also initialises it, but that path is
        // frame-gated; seeding here makes the engine self-sufficient
        // from the first scan. restore() merges freshest-wins (D31-2),
        // so a second initialise is a safe no-op.
        AdapterHealthRegistry.initialize(
            context.applicationContext
        )

        handler.post(
            runnable
        )

        RuntimeLogger.log(
            "Recovery engine started",
            "RECOVERY"
        )
    }
}
