package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import java.util.concurrent.atomic.AtomicBoolean

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
        if (captureReady.compareAndSet(false, true)) {
            transition("G2 CAPTURE_READY")
        }
        evaluate()
    }

    @Synchronized
    fun reportBoosterReady() {
        if (boosterReady.compareAndSet(false, true)) {
            transition("G3 BOOSTER_READY")
        }
        evaluate()
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
     * Reverse ignition order: execution -> arbitration -> gameplay ->
     * vision -> stores -> diagnostics. Each reset is independently guarded
     * so one missing hook cannot abort the shutdown chain.
     */
    private fun resetRuntimeState() {
        try { com.assistant.execution.ContributionRegistry.clear() } catch (_: Throwable) {}
        try { GestureExecutionAuthority.reset() } catch (_: Throwable) {}
        try { MagneticFeetEngine.reset() } catch (_: Throwable) {}
        try { GameplayDecisionEngine.reset() } catch (_: Throwable) {}
        try { CrossingLaneAnalysisEngine.reset() } catch (_: Throwable) {}
        try { ActiveGestureControllerDiagnostics.reset() } catch (_: Throwable) {}
        try { SmartAssistMetrics.reset() } catch (_: Throwable) {}
    }

    @Synchronized
    fun runtimeState(): Map<String, Any> = mapOf(
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

    private fun evaluate() {
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
        try { GameplayDecisionEngine.gameplayActivationDiagnostics() } catch (_: Throwable) {}
        try { TrueTargetPassingEngine.currentReceiverRankingResult() } catch (_: Throwable) {}
        try { SmartAssistMetrics.snapshot() } catch (_: Throwable) {}
    }

    private fun transition(stage: String) {
        lastTransition = stage
        lastTransitionMs = System.currentTimeMillis()
        RuntimeLogger.log("RuntimeCoordinator: $stage", "RUNTIME")
    }
}
