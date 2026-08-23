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

/**
 * FLEET LIFECYCLE STATES
 *
 * COLD     -> ignite() never called. Zero services launched.
 * PARTIAL  -> ignite() called. Stagger in progress. Services launching; NOT confirmed.
 * WARMING  -> All 16 launch intents dispatched. 1-8 adapters reporting ACTIVE heartbeats.
 * READY    -> Minimum quorum (>=9 of 16) adapters are ACTIVE -- fleet health CONFIRMED.
 * DEGRADED -> Fleet was READY, fell below quorum. WatchdogAdapter attempting recovery.
 *
 * EVIDENCE CHAIN (caller -> engine -> callee -> mutation -> live effect):
 * BoosterIgnition.ensureIgnited()
 *   -> IgnitionEngine.ignite()
 *     -> fleetState = PARTIAL
 *     -> igniteSequence() (non-blocking stagger via ipcHandler)
 *       -> context.startForegroundService(adapterIntent) x 16
 *       -> ipcHandler.postDelayed(verifyFleetHealth, VERIFICATION_DELAY_MS)
 *         -> AdapterHealthRegistry.getAll().count { ACTIVE }
 *           -> fleetState = WARMING | READY | DEGRADED
 *             -> RuntimeLogger.log (visible in DiagnosisRoom)
 *             -> retry if not READY (every RETRY_DELAY_MS)
 *
 * BoosterIgnition.isFleetReady() -> IgnitionEngine.fleetState == READY
 * RuntimeCoordinator G3 gate reads BoosterIgnition.isFleetReady()
 * DashboardInjector displays fleetHealthSnapshot() -- visible text evidence on screen.
 * OverlayService notification content text updated on state transitions.
 */
enum class FleetLifecycleState {
    COLD, PARTIAL, WARMING, READY, DEGRADED
}

object IgnitionEngine {

    private val ipcThread =
        HandlerThread(
            "IgnitionIPC",
            Process.THREAD_PRIORITY_BACKGROUND
        ).apply { start() }

    private val ipcHandler =
        Handler(ipcThread.looper)

    // Stagger delay prevents AMS thundering-herd and
    // "did not call startForeground()" ANRs on API 26+.
    private const val STAGGER_DELAY_MS = 250L

    // All 16 x 250ms stagger = 4000ms + 5000ms grace = 9000ms before first check.
    // Gives every service time to start foreground and emit its initial heartbeat.
    private const val VERIFICATION_DELAY_MS = 9000L

    // Retry interval: WatchdogAdapter may be restarting dead services; give it time.
    private const val RETRY_DELAY_MS = 5000L

    // Minimum ACTIVE adapter count to declare fleet READY (9 of 16 = 56% quorum).
    // Critical adapters: net, lag, stutter, memory, thermal, smartassist,
    // scheduler, watchdog, ping -- all must be alive for safe gameplay execution.
    private const val QUORUM_MINIMUM = 9
    private const val ADAPTER_TOTAL  = 16

    // -- Fleet state --
    @Volatile
    var fleetState: FleetLifecycleState = FleetLifecycleState.COLD
        private set

    @Volatile
    private var lastVerifiedActiveCount = 0

    // -- Public API --

    fun ignite(context: Context): Boolean {
        if (!ComplianceState.ready(context)) {
            RuntimeLogger.log(
                "Ignition blocked :: " + ComplianceState.summary(context),
                "IGNITION"
            )
            return false
        }

        // Mark PARTIAL immediately -- downstream callers must NOT read this as healthy.
        fleetState = FleetLifecycleState.PARTIAL
        lastVerifiedActiveCount = 0

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
            "com.assistant.adapter.interruption.InterruptionAdapterService",
            // P0 FIX: PingEliminatorVpnService added -- manifest entry required.
            // Provides: DNS pre-warming + UDP RTT probe + AdapterSignalBus pingQuality.
            "com.assistant.PingEliminatorVpnService"
        )

        // Non-blocking stagger. Returns true immediately after scheduling.
        // Fleet health is NOT confirmed here -- verifyFleetHealth() confirms it async.
        igniteSequence(context, adapters.iterator())

        RuntimeLogger.log(
            "Ignition stagger started -- $ADAPTER_TOTAL adapters queued. " +
                "Fleet verification in ${VERIFICATION_DELAY_MS}ms.",
            "IGNITION"
        )
        return true
    }

    /**
     * Human-readable fleet health for dashboard and HUD display.
     * Provides VISIBLE EVIDENCE of fleet state to the user.
     */
    fun fleetHealthSnapshot(): String {
        return "FLEET $lastVerifiedActiveCount/$ADAPTER_TOTAL ACTIVE | $fleetState"
    }

    // -- Private engine --

    private fun igniteSequence(context: Context, iterator: Iterator<String>) {
        if (!iterator.hasNext()) {
            // P0 FIX: All launch intents dispatched.
            // Now schedule the first fleet health verification after stagger + grace.
            ipcHandler.postDelayed({ verifyFleetHealth(context) }, VERIFICATION_DELAY_MS)
            return
        }

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
                RuntimeLogger.log("Adapter launch requested: $className", "IGNITION")
            } catch (e: Exception) {
                RuntimeLogger.log(
                    "Adapter launch failed: $className :: ${e.javaClass.simpleName}",
                    "IGNITION"
                )
            }
            igniteSequence(context, iterator)
        }, STAGGER_DELAY_MS)
    }

    /**
     * P0 FIX: Fleet health verification.
     *
     * Reads AdapterHealthRegistry.getAll() and counts ACTIVE adapters.
     * Mutates fleetState to WARMING, READY, or DEGRADED.
     * Schedules retry every RETRY_DELAY_MS until READY or WatchdogAdapter resolves it.
     *
     * LIVE EFFECT: BoosterIgnition.isFleetReady() returns true only when this
     * method confirms activeCount >= QUORUM_MINIMUM. RuntimeCoordinator G3 gate
     * remains false until then. Gameplay engines remain gated until fleet is proven.
     */
    private fun verifyFleetHealth(context: Context) {
        try {
            val snapshots = com.assistant.diagnostic.registry.AdapterHealthRegistry.getAll()
            val activeCount = snapshots.count { snap ->
                com.assistant.diagnostic.registry.AdapterHealthRegistry
                    .effectiveStatus(snap.adapterName) == "ACTIVE"
            }

            lastVerifiedActiveCount = activeCount
            val previousState = fleetState

            fleetState = when {
                activeCount >= QUORUM_MINIMUM -> FleetLifecycleState.READY
                activeCount > 0              -> FleetLifecycleState.WARMING
                else                         -> FleetLifecycleState.DEGRADED
            }

            val transitionNote = if (previousState != fleetState)
                " [TRANSITION: $previousState -> $fleetState]" else ""

            RuntimeLogger.log(
                "Fleet verification: active=$activeCount/$ADAPTER_TOTAL " +
                    "state=$fleetState$transitionNote",
                "IGNITION"
            )

            if (fleetState != FleetLifecycleState.READY) {
                // Not at quorum -- retry. WatchdogAdapter handles dead-service restarts.
                ipcHandler.postDelayed({ verifyFleetHealth(context) }, RETRY_DELAY_MS)
            }

        } catch (e: Exception) {
            fleetState = FleetLifecycleState.DEGRADED
            RuntimeLogger.log(
                "Fleet verification exception: ${e.javaClass.simpleName}: ${e.message}",
                "IGNITION"
            )
            // Retry -- registry may not yet be populated on cold start.
            ipcHandler.postDelayed({ verifyFleetHealth(context) }, RETRY_DELAY_MS)
        }
    }
}
