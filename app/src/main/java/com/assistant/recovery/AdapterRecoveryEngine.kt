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

    // No63-D10: `started` was a plain var and start() unsynchronised;
    // two concurrent start() calls could both pass the guard and post
    // the scan runnable twice. App.onCreate calls it once on the main
    // thread today, but the guard is the only thing stopping a double
    // scan — make it airtight.
    @Volatile
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

    // No63-D11: the 30s scan interval was a bare literal in the re-post;
    // named alongside COOLDOWN_MS so both scan timings read as policy.
    private const val SCAN_INTERVAL_MS =
        30000L

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

        // No63-D8: the className lookup used to sit AFTER the
        // activeLaunches.add() below — an unknown name (a 4th adapter
        // name in the persisted store) returned early with its entry
        // still in the set, so every future launch of that name was
        // refused forever. Resolve first; only a launchable name is
        // guarded.
        val className =
            adapterMap[adapterName]
                ?: return

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

        } catch (t: Throwable) {

            // No63-D9 (launchAdapter arm): this used to catch Exception
            // only. An Error escaping startForegroundService left the
            // activeLaunches entry in place with no removal — the adapter
            // could never be launched again. Catch Throwable so the
            // guard is always released on failure.
            RuntimeLogger.log(
                "Recovery failed :: $adapterName :: ${t.javaClass.simpleName}",
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

                } catch (t: Throwable) {
                    // No63-D9 (runnable arm): this used to catch Exception
                    // only, and silently. An Error escaping the scan
                    // skipped the postDelayed() re-post below and killed
                    // the 30s scan loop permanently — the same class of
                    // death as the F38-D1 busRunnable failure. The loop
                    // must survive anything the scan throws, and the
                    // failure is logged instead of swallowed.
                    RuntimeLogger.log(
                        "Recovery scan failed :: ${t.javaClass.simpleName}",
                        "RECOVERY"
                    )
                }

                handler.postDelayed(
                    this,
                    SCAN_INTERVAL_MS
                )
            }
        }

    // No63-D10: start() is @Synchronized with the @Volatile `started`
    // guard above, so two concurrent callers cannot both post the scan
    // runnable.
    @Synchronized
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
