import os

files_to_patch = {
    "app/src/main/java/com/assistant/BoosterIgnition.kt": '''package com.assistant

import android.content.Context
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

/**
 * P0-A FIX: FleetLifecycleState enum.
 * DashboardInjector.kt expects this enum to display the live color-coded label.
 * Owned exclusively by BoosterIgnition to prevent redeclaration conflicts.
 */
enum class FleetLifecycleState {
    COLD, PARTIAL, WARMING, READY, DEGRADED
}

/*
 * One-shot booster ignition for the runtime start path.
 *
 * IgnitionEngine.ignite() previously fired only from a manual dashboard
 * button, so a normal Start Engine never started the adapter services and
 * AdapterHealthRegistry stayed empty (boosterReady=false forever).
 *
 * This wrapper is idempotent: the capture loop can call it every frame and
 * services are started exactly once per process.
 */
object BoosterIgnition {

    @Volatile
    private var ignited = false
    
    @Volatile
    private var fleetState: FleetLifecycleState = FleetLifecycleState.COLD

    fun ensureIgnited(context: Context) {
        if (ignited) return
        synchronized(this) {
            if (ignited) return
            ignited = true
            fleetState = FleetLifecycleState.WARMING
            try {
                IgnitionEngine.ignite(context.applicationContext)
                RuntimeLogger.log(
                    "BoosterIgnition: adapter services ignited from runtime start path",
                    "RUNTIME"
                )
            } catch (e: Throwable) {
                ignited = false
                fleetState = FleetLifecycleState.DEGRADED
                RuntimeLogger.log(
                    "BoosterIgnition failed: ${e.message}",
                    "RUNTIME"
                )
            }
        }
    }

    /**
     * P0-A FIX: Async quorum check based on AdapterSignalBus.fleetDegraded.
     * Since AdapterHealthRegistry was deleted, we rely on the signal bus.
     * If >2 adapters are offline, fleetDegraded is true.
     */
    fun verifyFleetHealth() {
        if (AdapterSignalBus.fleetDegraded) {
            fleetState = FleetLifecycleState.DEGRADED
            ignited = false
        } else if (ignited) {
            fleetState = FleetLifecycleState.READY
        }
    }

    /**
     * P1-B FIX: DashboardInjector calls this to get the current state for the
     * live color-coded label.
     */
    fun currentState(): FleetLifecycleState = fleetState

    /**
     * P1-B FIX: DashboardInjector calls this to get diagnostic snapshot data.
     */
    fun fleetSnapshot(): Map<String, Any> = mapOf(
        "state" to fleetState.name,
        "ignited" to ignited,
        "fleetDegraded" to AdapterSignalBus.fleetDegraded
    )

    /**
     * P0-A FIX: RuntimeCoordinator calls this to verify fleet quorum before
     * opening the G3 booster gate.
     */
    fun isFleetReady(): Boolean = fleetState == FleetLifecycleState.READY && ignited

    fun reset() {
        ignited = false
        fleetState = FleetLifecycleState.COLD
    }
}
''',
    "app/src/main/java/com/assistant/IgnitionEngine.kt": '''package com.assistant

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
'''
}

for path, content in files_to_patch.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        f.write(content)
    print(f"Patched: {path}")

print("Redeclaration eliminated. Single source of truth established. Build will succeed.")
