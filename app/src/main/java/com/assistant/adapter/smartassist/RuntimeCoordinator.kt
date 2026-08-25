package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import java.util.concurrent.atomic.AtomicBoolean
import com.assistant.runtime.GameplayEngineRegistry

/*
 * Owns ignition order, gate state, and shutdown for the gameplay runtime.
 * Contains no gameplay logic. Gates:
 *   G0 permissions  G1 accessibility  G2 capture  G3 booster
 *   G4 engines warm G5 bus enabled    G6 runtime ready
 * For this step G0/G3 are recorded but non-blocking: a bound accessibility
 * service plus flowing projection frames already prove the permission set.
 */
object RuntimeCoordinator {

    private val permissionsVerified = AtomicBoolean(false)
    private val accessibilityReady = AtomicBoolean(false)
    private val captureReady = AtomicBoolean(false)
    private val boosterReady = AtomicBoolean(false)
    private val enginesWarm = AtomicBoolean(false)
    private val busEnabled = AtomicBoolean(false)
    private val runtimeReady = AtomicBoolean(false)

    private var startExecutionLoop: (() -> Unit)? = null
    private var stopExecutionLoopCallback: (() -> Unit)? = null

    // recursion guard: evaluate() must never re-enter itself
    private val evaluating = AtomicBoolean(false)

    @Volatile private var lastTransition: String = "cold"
    @Volatile private var lastTransitionMs: Long = 0L

    @Synchronized
    fun attachExecutionLoop(start: () -> Unit, stop: () -> Unit) {
        startExecutionLoop = start
        stopExecutionLoopCallback = stop
    }

    @Synchronized
    fun reportPermissionsVerified() {
        if (permissionsVerified.compareAndSet(false, true)) {
            transition("G0 PERMISSIONS_VERIFIED")
        }
        evaluate()
    }

    @Synchronized
    fun reportAccessibilityReady() {
        if (accessibilityReady.compareAndSet(false, true)) {
            transition("G1 ACCESSIBILITY_READY")
        }
        evaluate()
    }

    @Synchronized
    fun reportCaptureReady() {
        // Fires on EVERY captured frame. Once the runtime is up there is nothing
        // left to evaluate, so skip the work instead of re-scanning the registry.
        if (captureReady.compareAndSet(false, true)) {
            transition("G2 CAPTURE_READY")
            evaluate()
        } else if (!runtimeReady.get()) {
            evaluate()
        }
    }

    @Synchronized
    fun reportBoosterReady() {
        // Only a genuine state CHANGE may trigger evaluation. Calling evaluate()
        // unconditionally here is what closed the recursion cycle.
        if (boosterReady.compareAndSet(false, true)) {
            transition("G3 BOOSTER_READY")
            evaluate()
        }
    }

    @Synchronized
    fun refreshBoosterReadyFromRegistry() {
        // P0 FIX: BoosterIgnition.isFleetReady() is the SINGLE authority for G3.
        // PREVIOUS BUG: AdapterHealthRegistry.getAllLive().any { heartbeat <= 120s }
        //   -- one stale adapter heartbeat opened the gate, bypassing fleet quorum.
        // FIXED: fleet quorum (>=9/16 ACTIVE) AND ignited latch confirmed by
        //   IgnitionEngine.verifyFleetHealth() via BoosterIgnition.isFleetReady().
        // DEGRADED re-opening: isFleetReady() resets ignited=false on DEGRADED,
        //   so boosterReady is forced false here too, keeping the display accurate.
        // NOTE: once busEnabled=true, evaluateInner() returns early on the
        //   busEnabled check -- so re-opening boosterReady does NOT stop running
        //   engines (intentional: WatchdogAdapter handles mid-match recovery).
        // P0-A WIRING FIX (FIELD-STALL ROOT CAUSE, TASK-CLOSURE TRACED):
        // verifyFleetHealth() is the ONLY transition that can promote
        // fleetState WARMING -> READY. Its documented owner is this G3 refresh
        // path ("RuntimeCoordinator calls this to verify fleet quorum before
        // opening the G3 booster gate") but the call was missing, so
        // isFleetReady() stayed false forever and the runtime stalled at
        // G2_CAPTURE_READY (booster-not-ready, bus-idle, execution starved).
        try {
            com.assistant.BoosterIgnition.verifyFleetHealth()
        } catch (_: Throwable) { }

        val healthy = try {
            com.assistant.BoosterIgnition.isFleetReady()
        } catch (_: Throwable) { false }

        if (healthy) {
            if (boosterReady.compareAndSet(false, true)) {
                transition("G3 BOOSTER_READY [fleet-quorum confirmed by BoosterIgnition]")
            }
        } else {
            // Fleet not ready (COLD/WARMING/DEGRADED) -- re-open gate to reflect reality.
            if (boosterReady.getAndSet(false)) {
                transition("G3 BOOSTER_GATE_OPENED [fleet not ready / degraded]")
            }
        }
    }

    @Synchronized
    fun reportAccessibilityLost() {
        accessibilityReady.set(false)
        busEnabled.set(false)
        runtimeReady.set(false)
        transition("G1 lost - runtime paused")
    }

    @Synchronized
    fun shutdown() {
        stopExecutionLoopCallback?.invoke()
        CentralExecutionBus.stop()
        busEnabled.set(false)
        runtimeReady.set(false)
        captureReady.set(false)
        enginesWarm.set(false)
        // P0 FIX: Reset G3 gate AND BoosterIgnition ignited latch on shutdown.
        // PREVIOUS BUG: BoosterIgnition.reset() was never called here, so ignited=true
        //   survived a shutdown. Next cold-start: isFleetReady() returned true immediately
        //   (ignited still latched), bypassing fleet quorum re-verification entirely.
        boosterReady.set(false)
        try { com.assistant.BoosterIgnition.reset() } catch (_: Throwable) {}
        resetRuntimeState()
        transition("RUNTIME_OFF")
    }

    /*
     * Shutdown is the exact reverse of warmUpEngines() ignition order:
     * loop/frame/registry first, then gameplay engines, then stores/diagnostics.
     * Each guarded so one missing hook cannot abort the chain.
     */
    private fun resetRuntimeState() {
        // 1. Decision plumbing (last things ignited -> first reset)
        try { RuntimeDecisionLoop.reset() } catch (_: Throwable) {}
        try { com.assistant.runtime.GameplayEngineRegistry.resetAll() } catch (_: Throwable) {}
        try { FrameAssembler.reset() } catch (_: Throwable) {}
        try { BallTelemetryBridge.reset() } catch (_: Throwable) {}
        try { com.assistant.events.EventHubs.resetAll() } catch (_: Throwable) {}
        try { com.assistant.execution.ContributionRegistry.clear() } catch (_: Throwable) {}
        try { GestureExecutionAuthority.reset() } catch (_: Throwable) {}

        // 2. Gameplay engines (reverse of ignition)
        try { GameplayDecisionEngine.reset() } catch (_: Throwable) {}
        try { MagneticFeetEngine.reset() } catch (_: Throwable) {}
        try { OverloadPlaystyleEngine.reset() } catch (_: Throwable) {}
        try { CrossingLaneAnalysisEngine.reset() } catch (_: Throwable) {}

        // 3. Diagnostics
        try { ActiveGestureControllerDiagnostics.reset() } catch (_: Throwable) {}
        try { SmartAssistMetrics.reset() } catch (_: Throwable) {}
        try { RuntimeHealthMonitor.reset() } catch (_: Throwable) {}
    }

    @Synchronized
    fun runtimeState(): Map<String, Any> {
        // Heal the booster latch lazily: the adapters usually heartbeat AFTER
        // this runtime reached G6, and no gate event fires again after that -
        // so without this re-check boosterReady stayed false forever even
        // while every adapter was alive and heartbeating.
        if (!boosterReady.get()) {
            refreshBoosterReadyFromRegistry()
        }
        return mapOf(
            "permissionsVerified" to permissionsVerified.get(),
            "accessibilityReady" to accessibilityReady.get(),
            "captureReady" to captureReady.get(),
            "boosterReady" to boosterReady.get(),
            "enginesWarm" to enginesWarm.get(),
            "busEnabled" to busEnabled.get(),
            "runtimeReady" to runtimeReady.get(),
            "lastTransition" to lastTransition,
            "lastTransitionMs" to lastTransitionMs,
            "busPending" to CentralExecutionBus.pendingCount()
        )
    }

    private fun evaluate() {
        // Hard stop against any future cycle. @Synchronized does NOT help here:
        // Java monitors are reentrant, so the same thread re-enters freely.
        if (!evaluating.compareAndSet(false, true)) return
        try {
            evaluateInner()
        } finally {
            evaluating.set(false)
        }
    }

    private fun evaluateInner() {
        refreshBoosterReadyFromRegistry()
        if (!accessibilityReady.get() || !captureReady.get()) return
        // P0 FIX: G3 gate is now an ACTUAL execution gate, not a display-only flag.
        // Engines must NOT start until BoosterIgnition confirms fleet quorum.
        // Once busEnabled=true this check is already bypassed by the busEnabled gate below,
        // so this ONLY affects cold-start -- engines will not fire with a dead fleet.
        if (!boosterReady.get()) return
        if (busEnabled.get()) return

        if (!enginesWarm.get()) {
            warmUpEngines()
            enginesWarm.set(true)
            transition("G4 ENGINES_WARM")
        }

        CentralExecutionBus.start()
        startExecutionLoop?.invoke()
        busEnabled.set(true)
        transition("G5 BUS_ENABLED")

        runtimeReady.set(true)
        transition("G6 RUNTIME_READY")
        // PHASE4B: agent already started in OverlayService.onCreate() — no duplicate start here
    }

    /*
     * Deterministic read-only ignition: stores -> vision snapshots ->
     * gameplay engines -> diagnostics. Touching each Kotlin object here
     * forces class loading in a fixed order, so the first real frame pays
     * no lazy-init cost. VisionCore itself is warmed by the first frame,
     * which is a precondition of reaching this point (G2).
     */
    private fun warmUpEngines() {
        // Unified registry ownership: all contributor registrations and warm-ups 
        // are now handled atomically by AppContributorRegistration to prevent 
        // dual-initialization races and warm-up idempotency issues.
        // This function now strictly handles read-only ignition for stores and engines only.
        try { TelemetryRepository.current() } catch (_: Throwable) {}
        try { SceneTracker.current() } catch (_: Throwable) {}
        try { Phase3WorldStateStore.current() } catch (_: Throwable) {}
        try { SmartAssistRepository.enabled() } catch (_: Throwable) {}
        try { CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot() } catch (_: Throwable) {}
        try { MagneticFeetEngine.magneticFeetSnapshot() } catch (_: Throwable) {}
        try { OverloadPlaystyleEngine.overloadRuntimeSnapshot() } catch (_: Throwable) {}
        try { GameplayDecisionEngine.gameplayActivationDiagnostics() } catch (_: Throwable) {}
        try { TrueTargetPassingEngine.currentReceiverRankingResult() } catch (_: Throwable) {}
        try { SmartAssistMetrics.snapshot() } catch (_: Throwable) {}
    }

    private fun transition(stage: String) {
        lastTransition = stage
        lastTransitionMs = System.currentTimeMillis()
        // Task 14: gate transitions are RUNTIME-channel events.
        try {
            com.assistant.events.RuntimeEventHub.emit("gate", stage)
        } catch (_: Throwable) {
            RuntimeLogger.log("RuntimeCoordinator: $stage", "RUNTIME")
        }
    }
}
