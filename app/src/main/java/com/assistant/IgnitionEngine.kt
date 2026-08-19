package com.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.assistant.compliance.ComplianceState
import com.assistant.diagnostic.RuntimeLogger

object IgnitionEngine {

    private val ipcThread =
        HandlerThread(
            "IgnitionIPC",
            Process.THREAD_PRIORITY_BACKGROUND
        ).apply { start() }

    private val ipcHandler =
        Handler(ipcThread.looper)

    // UPGRADE: Stagger delay prevents ActivityManagerService (AMS) thundering herd 
    // and "Context.startForegroundService() did not then call Service.startForeground()" ANRs.
    private const val STAGGER_DELAY_MS = 250L

    fun ignite(context: Context) {
        if (!ComplianceState.ready(context)) {
            RuntimeLogger.log(
                "Ignition blocked :: " + ComplianceState.summary(context),
                "IGNITION"
            )
            return
        }

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

        // UPGRADE: Replaced blocking Thread.sleep loop with non-blocking Handler.postDelayed chain.
        // This prevents holding the IPC thread hostage for ~4 seconds and eliminates 
        // thread-starvation risks during app startup.
        igniteSequence(context, adapters.iterator())
    }

    private fun igniteSequence(context: Context, iterator: Iterator<String>) {
        if (!iterator.hasNext()) return

        ipcHandler.postDelayed({
            val className = iterator.next()
            val intent = Intent().apply {
                component = ComponentName(context.packageName, className)
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

            // Recursively schedule the next adapter
            igniteSequence(context, iterator)
            
        }, STAGGER_DELAY_MS)
    }
}
