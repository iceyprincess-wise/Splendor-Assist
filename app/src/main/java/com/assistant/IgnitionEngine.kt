package com.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger

object IgnitionEngine {

    private val ipcThread =
        HandlerThread(
            "IgnitionIPC",
            Process.THREAD_PRIORITY_BACKGROUND
        ).apply { start() }

    private val ipcHandler =
        Handler(ipcThread.looper)

    fun ignite(context: Context) {

        ipcHandler.post {

            val adapters = listOf(

                "com.assistant.adapter.net.NetAdapterService",
                "com.assistant.adapter.input.InputAdapterService",
                "com.assistant.adapter.lmk.LmkAdapterService",
                "com.assistant.adapter.sync.SyncAdapterService",

                "com.assistant.adapter.ping.PingAdapterService",
                "com.assistant.adapter.stutter.StutterAdapterService",
                "com.assistant.adapter.lag.LagAdapterService",
                "com.assistant.adapter.boot.BootAdapterService",
                "com.assistant.adapter.watchdog.WatchdogAdapterService",

                "com.assistant.adapter.memory.MemoryAdapterService",
                "com.assistant.adapter.thermal.ThermalAdapterService",
                "com.assistant.adapter.battery.BatteryAdapterService",
                "com.assistant.adapter.scheduler.SchedulerAdapterService",
                "com.assistant.adapter.smartassist.SmartAssistAdapterService",

                "com.assistant.adapter.interruption.InterruptionAdapterService"
            )

            adapters.forEach { className ->

                val intent =
                    Intent().apply {
                        component =
                            ComponentName(
                                context.packageName,
                                className
                            )
                    }

                try {

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }

                    RuntimeLogger.log(
                        "Adapter launch requested: $className",
                        "IGNITION"
                    )

                } catch (e: Exception) {

                    RuntimeLogger.log(
                        "Adapter launch failed: $className :: ${e.javaClass.simpleName}",
                        "IGNITION"
                    )
                }
            }
        }
    }
}
