package com.assistant

import android.content.Context
import com.assistant.diagnostic.RuntimeLogger

/*
 * BOOSTER IGNITION
 *
 * P0 FIX -- Fleet-health proof gating.
 *
 * PREVIOUS BUG (PROVEN from repo):
 *   ignited=true was latched the moment IgnitionEngine.ignite() returned true.
 *   ignite() returns true after *scheduling* service launches -- not after
 *   services started foreground or emitted a heartbeat. AdapterHealthRegistry
 *   stayed empty. G3 boosterReady became true with zero real adapters alive.
 *   RuntimeCoordinator proceeded to READY state with a dead fleet.
 *
 * FIX:
 *   ignited=true is now set ONLY when IgnitionEngine.fleetState == READY,
 *   meaning at least QUORUM_MINIMUM adapters have emitted a live heartbeat
 *   to AdapterHealthRegistry. This is proven by AdapterHealthRegistry.effectiveStatus().
 *
 * IDEMPOTENT: ensureIgnited() is called every frame from capture loop.
 *   - Cold start: schedules stagger, returns immediately.
 *   - After stagger: polls fleetState. Latches ignited when READY.
 *   - Permanent latch: once ignited, no further registry reads.
 *
 * VISIBLE EVIDENCE: isFleetReady() is consumed by RuntimeCoordinator G3 gate.
 *   Dashboard shows fleetHealthSnapshot(). HUD overlay transitions color.
 */
object BoosterIgnition {

    @Volatile
    private var ignited = false

    @Volatile
    private var ignitionScheduled = false

    // -- PUBLIC API --

    /**
     * Called from OverlayService capture loop (every frame, idempotent).
     *
     * COLD:    schedules ignition. Returns immediately (not ready yet).
     * PARTIAL/WARMING: checks fleet state. Latches if READY.
     * READY:   noop (ignited=true prevents re-entry).
     */
    fun ensureIgnited(context: Context) {
        if (ignited) return

        synchronized(this) {
            if (ignited) return

            // Step 1: Schedule service launches if not already done.
            if (!ignitionScheduled) {
                val success = IgnitionEngine.ignite(context.applicationContext)
                if (success) {
                    ignitionScheduled = true
                    RuntimeLogger.log(
                        "BoosterIgnition: ignition stagger scheduled -- fleet not confirmed yet",
                        "RUNTIME"
                    )
                } else {
                    RuntimeLogger.log(
                        "BoosterIgnition: ComplianceState gate not satisfied -- retry later",
                        "RUNTIME"
                    )
                }
                return // NOT ignited yet -- fleet health must be verified first.
            }

            // Step 2: Fleet was scheduled. Check if verification confirmed quorum.
            val state = IgnitionEngine.fleetState
            if (state == FleetLifecycleState.READY) {
                // P0 FIX: Only latch here -- after real heartbeats confirmed.
                ignited = true
                RuntimeLogger.log(
                    "BoosterIgnition: fleet READY confirmed -- ignited=true latched. " +
                        IgnitionEngine.fleetHealthSnapshot(),
                    "RUNTIME"
                )
            } else {
                RuntimeLogger.log(
                    "BoosterIgnition: fleet not READY yet -- $state. " +
                        IgnitionEngine.fleetHealthSnapshot(),
                    "RUNTIME"
                )
            }
        }
    }

    /**
     * P0 FIX: isFleetReady() is the single truth source for G3 boosterReady gate.
     * Returns true ONLY when:
     *   1. ignited=true (latched by READY confirmation), AND
     *   2. Fleet state is still READY (not fallen to DEGRADED since).
     *
     * DEGRADED re-opens the gate -- prevents gameplay engines from running
     * while the fleet is broken.
     */
    fun isFleetReady(): Boolean {
        if (!ignited) return false
        val currentState = IgnitionEngine.fleetState
        if (currentState == FleetLifecycleState.DEGRADED) {
            RuntimeLogger.log(
                "BoosterIgnition: fleet fell to DEGRADED -- gate re-opened",
                "RUNTIME"
            )
            ignited = false
            ignitionScheduled = false
            return false
        }
        return true
    }

    /**
     * Fleet lifecycle state -- for DashboardInjector and HUD display.
     * This is VISIBLE EVIDENCE shown directly to the user.
     */
    fun currentState(): FleetLifecycleState = IgnitionEngine.fleetState

    /** Dashboard display string */
    fun fleetSnapshot(): String = IgnitionEngine.fleetHealthSnapshot()

    /** Hard reset on engine stop (called by RuntimeCoordinator.shutdown()) */
    fun reset() {
        synchronized(this) {
            ignited = false
            ignitionScheduled = false
        }
    }
}
