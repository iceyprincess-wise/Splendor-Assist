package com.assistant.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry

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

            "adapter_net" to
                "com.assistant.adapter.net.NetAdapterService",

            "adapter_input" to
                "com.assistant.adapter.input.InputAdapterService",

            "adapter_lmk" to
                "com.assistant.adapter.lmk.LmkAdapterService",

            "adapter_sync" to
                "com.assistant.adapter.sync.SyncAdapterService",

            "adapter_ping" to
                "com.assistant.adapter.ping.PingAdapterService",

            "adapter_stutter" to
                "com.assistant.adapter.stutter.StutterAdapterService",

            "adapter_lag" to
                "com.assistant.adapter.lag.LagAdapterService",

            "adapter_boot" to
                "com.assistant.adapter.boot.BootAdapterService",

            "adapter_watchdog" to
                "com.assistant.adapter.watchdog.WatchdogAdapterService",

            "adapter_memory" to
                "com.assistant.adapter.memory.MemoryAdapterService",

            "adapter_thermal" to
                "com.assistant.adapter.thermal.ThermalAdapterService",

            "adapter_battery" to
                "com.assistant.adapter.battery.BatteryAdapterService",

            "adapter_scheduler" to
                "com.assistant.adapter.scheduler.SchedulerAdapterService",

            "adapter_smartassist" to
                "com.assistant.adapter.smartassist.SmartAssistAdapterService",

            "adapter_interruption" to
                "com.assistant.adapter.interruption.InterruptionAdapterService"
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

        handler.post(
            runnable
        )

        RuntimeLogger.log(
            "Recovery engine started",
            "RECOVERY"
        )
    }
}
