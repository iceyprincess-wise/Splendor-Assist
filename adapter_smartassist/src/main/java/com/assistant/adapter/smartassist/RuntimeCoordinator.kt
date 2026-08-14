package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import java.util.concurrent.atomic.AtomicBoolean
import com.assistant.runtime.GameplayEngineRegistry
import com.assistant.diagnostic.registry.AdapterHealthRegistry

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
        // Adapters heartbeat from their OWN processes; getAllLive() merges the
        // persisted cross-process snapshots. Only a FRESH heartbeat proves a
        // booster adapter is actually alive right now - an old file entry
        // from a previous session must not open the gate.
        val now = System.currentTimeMillis()
        val healthy =
            try {
                AdapterHealthRegistry.getAllLive().any {
                    now - it.lastHeartbeat <= 120_000L
                }
            } catch (_: Throwable) {
                false
            }

        // Set the gate DIRECTLY. Calling reportBoosterReady() from here created
        // the cycle: evaluate -> refresh -> report -> evaluate -> ...
        if (healthy && boosterReady.compareAndSet(false, true)) {
            transition("G3 BOOSTER_READY")
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
        try {
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.MagneticFeetContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.PassingContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ShotContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.SupportContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.DefenseContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.EvadeContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.AttackingVectorContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.CrossContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.AgilityContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.WingBlockContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.DashPressureContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.InterceptMatrixContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.TouchRecoveryContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.OverloadPlaystyleContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.TruePassContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ReceiverEngagementContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ForwardRunContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ShotOpportunityContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.DefenseAuthorityContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.ShotAnticipationContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.KeeperFeedbackContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.DashAnchorContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.SpeedCompensationContributor)
            // BATCH 4: instant intercept + build-up press + ball retention shield
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.InstantInterceptContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.BuildUpPressContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.BallRetentionShieldContributor)
            // BATCH S: TrueShot + TrueCross + SA Ultimate Corrector (#27-29)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.TrueShotContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.TrueCrossContributor)
            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor)
        } catch (_: Throwable) {}
        try { GameplayEngineRegistry.warmAll() } catch (_: Throwable) {}
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
