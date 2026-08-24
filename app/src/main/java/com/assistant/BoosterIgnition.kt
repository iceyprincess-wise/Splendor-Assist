package com.assistant

import android.content.Context
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger

/**
 * P0-A FIX: FleetLifecycleState enum restored.
 * DashboardInjector.kt expects this enum to display the live color-coded label.
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
