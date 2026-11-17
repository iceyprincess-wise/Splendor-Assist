package com.assistant

// F49_PATCH_V1























import android.os.SystemClock



import com.assistant.diagnostic.AdapterSignalBus




import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.execution.HybridExecutionTerminal

import com.assistant.runtime.*
import com.assistant.runtime.ActionClass

import com.assistant.runtime.EngineContribution

import com.assistant.runtime.GameplayEngineRegistry
import com.assistant.runtime.RuntimeFrame


















import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*





import kotlin.math.hypot






















object RuntimeDecisionLoop {

    private const val MAX_OPTICAL_VARIANCE_FOR_ACTION = 0.85f
    private var lastCaptureId = -1L
    // PARKED-LANE WIRING (D11): floor between triggerInstantExecution
    // calls. The trigger runs the whole legacy injectWinningVector (heavy)
    // and routes a direct legacy request, so it must not run every frame.
    private const val PARKED_TRIGGER_MIN_INTERVAL_MS = 250L
    private var lastParkedTriggerMs = 0L
    private val decisions = AtomicLong(0L)
    private val routed = AtomicLong(0L)
    private val idleNoContribution = AtomicLong(0L)
    private val idleUntrusted = AtomicLong(0L)
    // F37-D4/D6: the four loss exits below had no counter, so a DEC/ROUTED
    // gap could not be attributed. chooseNull splits the silent
    // chooseRequest() nulls out of idleNoContribution.
    private val gateBlocked = AtomicLong(0L)
    private val staleFrame = AtomicLong(0L)
    private val settingsExit = AtomicLong(0L)
    private val routeRejected = AtomicLong(0L)
    private val chooseNull = AtomicLong(0L)

    @Volatile private var lastAction: String = "none"
    @Volatile private var lastWeight: Float = 0f
    @Volatile private var lastUpdatedMs: Long = 0L
    private val gestureIntentIds = AtomicLong(0L)
    @Volatile private var activeGestureEnvelope: com.assistant.execution.GestureIntentEnvelope? = null

    private fun mintOrRefreshGestureEnvelope(now: Long): com.assistant.execution.GestureIntentEnvelope {
        val current = activeGestureEnvelope
        val next = if (current == null || current.isExpired(now)) {
            com.assistant.execution.GestureIntentEnvelope(gestureIntentIds.incrementAndGet(), now, now)
        } else current.copy(lastActivityElapsedMs = now)
        activeGestureEnvelope = next
        return next
    }

    @JvmStatic fun invalidateGestureEnvelopeOnManualTouch() { activeGestureEnvelope = null }


    /* Called once per assembled frame by OverlayService's capture loop. */
    @Synchronized fun onFrame(frame: RuntimeFrame): Boolean {
        ActionOutcomeVerifier.verify(frame)
        if (com.assistant.execution.UiExecutionGate.blocked()) { gateBlocked.incrementAndGet(); CentralExecutionBus.clearPending(); return false }
        if (frame.frameId <= lastCaptureId) { staleFrame.incrementAndGet(); return false }
        lastCaptureId = frame.frameId
        decisions.incrementAndGet()
        lastUpdatedMs = System.currentTimeMillis()

        if (!com.assistant.VisionTrust.isGameForeground() || !com.assistant.SmartAssistRepository.enabled()) {
            idleUntrusted.incrementAndGet()
            lastAction = "idle-untrusted"
            return false
        }

        if (!frame.trusted || frame.opticalVariance >= MAX_OPTICAL_VARIANCE_FOR_ACTION) {
            idleUntrusted.incrementAndGet()
            lastAction = "idle-untrusted"
            return false
        }

        // CONTROL-MAPPING GATE: the mapping/settings screen must never
        // receive gameplay gestures; trained in-match cluster arms the stack.
        if (ControlMappingTrainer.uiMode == ControlMappingTrainer.UiMode.SETTINGS) {
            settingsExit.incrementAndGet(); com.assistant.execution.UiExecutionGate.block("control-calibration"); return false
        }
        ControlMappingTrainer.observeOutcome(frame)

        // SPLENDOR_AUTHORITY_REACHES_ARBITRATION_V1 - the master authority
        // slider used to reach RuntimeObservation only, so it could not change
        // what the engine actually did. Scale the weight every arbiter reads
        // (weight = authority * confidence) by the operator authority. At the
        // 100% default the factor is 1.0, so default behaviour is unchanged.
        val authorityFactor =
            RuntimePerformanceCoordinator.authority().coerceIn(0, 100) / 100f
        val collected = GameplayEngineRegistry.collect(frame).filter { c ->
            !ActionOutcomeVerifier.cooling(c.actionClass)
        }.map { c -> c.copy(confidence = c.confidence * ActionOutcomeVerifier.confidenceFactor(c.actionClass)) }.filter { c ->
            requiredControlSlot(c) == 0 || (ControlMappingTrainer.matchActive && ControlMappingTrainer.isSlotTrained(requiredControlSlot(c)))
        }
        val contributions = if (authorityFactor >= 1f) collected else collected.map { c ->
            c.copy(authority = (c.authority * authorityFactor).coerceIn(0f, 1f))
        }
        val netHold = AdapterSignalBus.netIsHold
        
        // ConAI Meta-Arbiter decision loop
        val best = ConAIEngine.arbitrate(frame, contributions, netHold)

        // PARKED-LANE WIRING (D11): first live caller of
        // SmartAssistAccessibilityEngine.triggerInstantExecution (it had
        // zero call sites, so the sole parked offerer AutoEvade, inside
        // ActiveGestureController.injectWinningVector, never ran and
        // ContributionRegistry.pending never filled). Live chain:
        // OverlayService capture loop -> onFrame(frame) -> here ->
        // SmartAssistAccessibilityEngine.globalInstance (set in
        // onServiceConnected, SmartAssistAccessibilityEngine.kt:343) ->
        // triggerInstantExecution -> injectWinningVector ->
        // AutoEvadeEngine.monitorAttackingSpace parks (SMART_ASSIST ph7)
        // -> drainBest() below returns it in this same frame.
        // Fired only when best == null: a parked entry is fallback-only
        // (f23 precedence — it never outranks a live best), and the legacy
        // direct request the trigger also routes must never compete with
        // one. The threat + rate gates live in maybeTriggerParkedLane.
        if (best == null) maybeTriggerParkedLane(frame)

        val parked = ContributionRegistry.drainBest()

        val rawRequest = chooseRequest(best, parked, frame)
        val request = rawRequest?.copy(gestureEnvelope = mintOrRefreshGestureEnvelope(android.os.SystemClock.elapsedRealtime()))
        if (request == null) {
            idleNoContribution.incrementAndGet()

            lastAction = "idle-no-contribution"

            return false
        }

        // ── Crowding Zone detection ─────────────────────────────────
        // Runs BEFORE amplifiers. Arms the duration cap and updates
        // AdapterSignalBus.crowdingZone for LagVerdictEngine next poll.
        val inCrowdedZone = CrowdingZoneDetector.evaluate(frame)

        val finalRequest = request

        // ── Captaincy Skill amplification ────────────────────────────────
        // Compatibility timing policy driven by a local fatigue proxy.
        // This changes gesture duration; it cannot modify in-game fatigue.
        val captaincy = CaptaincySkillEngine.evaluate(frame)
        // teamLiftFactor: multiplicative duration scale — real terminal effect.
        // composureBoostMs: additive on top — both reach HybridExecutionTerminal.
        val resolvedRequest = if (captaincy.active) {
            val liftedDuration = (finalRequest.duration * captaincy.teamLiftFactor).toLong()
            finalRequest.copy(
                duration = (liftedDuration + captaincy.composureBoostMs)
                    .coerceIn(15L, 85L)
            )
        } else finalRequest

        // ── Crowding Zone saturation cap ──────────────────────────────
        // Penalty box / corner: both amplifiers peak simultaneously.
        // Cap at 45ms so inputs stay deliberate, not desperate spam.
        val terminalRequest = if (inCrowdedZone && resolvedRequest.duration > 45L) {
            resolvedRequest.copy(duration = resolvedRequest.duration.coerceAtMost(45L))
        } else resolvedRequest

        // Register before publishing: the bus consumer runs on another thread.
        ActionOutcomeVerifier.recordDispatch(terminalRequest, frame)
        val accepted = HybridExecutionTerminal.route(terminalRequest)
        if (!accepted) { routeRejected.incrementAndGet(); ActionOutcomeVerifier.cancelDispatch(terminalRequest.actionId) }
        if (accepted) {
            routed.incrementAndGet()
            try {
                com.assistant.events.GameplayEventHub.emit(
                    "routed",
                    "source=${request.source} phase=${request.phase}"
                )
            } catch (_: Throwable) {
            }
        }
        lastAction = describe(best, parked)
        lastWeight = best?.weight ?: 0f
        return accepted
    }

    private fun chooseRequest(
        best: EngineContribution?,
        parked: ExecutionRequest?,
        frame: RuntimeFrame
    ): ExecutionRequest? {
        // D-23-02/D3: the drained entry is a PARKED contribution (the only
        // source that parks is SMART_ASSIST), not an emergency - emergency
        // sources (GK/INT) bypass the map entirely. The old code compared
        // its priority against SMART_ASSIST's (90 > 90), which could never
        // be true, so the branch was dead and the drained entry was
        // silently dropped whenever a ConAI best existed. Precedence is
        // now explicit: a parked entry is the fallback when arbitration
        // has no best; it never outranks a live best (bus rings already
        // give GK/INT priority over SMART_ASSIST).
        if (parked != null && best == null) return parked
        if (best == null) return null

        // Contributor targets carry action-specific intent (goal, receiver, movement).
        var leadX = best.targetX
        var leadY = best.targetY
        var startX = com.assistant.vision.CameraProfile.captureWidthOrFallback() * ControlMappingTrainer.joystickCenterX()
        var startY = com.assistant.vision.CameraProfile.captureHeightOrFallback() * ControlMappingTrainer.joystickCenterY()
        val controlSlot = requiredControlSlot(best)
        val mover = frame.entities.firstOrNull { it.id == (if (frame.hasBall) frame.ownerId else frame.defenderId) && it.teammate }
        val aimOriginX = best.aimOriginX ?: if (controlSlot != 0) frame.ballX else mover?.x
        val aimOriginY = best.aimOriginY ?: if (controlSlot != 0) frame.ballY else mover?.y
        if (aimOriginX != null && aimOriginY != null) {
            if (controlSlot != 0) {
                if (!ControlMappingTrainer.matchActive || !ControlMappingTrainer.isSlotTrained(controlSlot)) { chooseNull.incrementAndGet(); return null }
                val center = ControlMappingTrainer.slotCenter(controlSlot)
                startX = center.first * frameWidth()
                startY = center.second * frameHeight()
            }
            val dx = best.targetX - aimOriginX
            val dy = best.targetY - aimOriginY
            val distance = hypot(dx, dy)
            if (!distance.isFinite() || distance <= 0f) { chooseNull.incrementAndGet(); return null }
            val radius = minOf(frameWidth(), frameHeight()) * 0.08f
            leadX = (startX + dx / distance * radius).coerceIn(0f, frameWidth() - 1f)
            leadY = (startY + dy / distance * radius).coerceIn(0f, frameHeight() - 1f)
        }
        if (aimOriginX == null || aimOriginY == null) { chooseNull.incrementAndGet(); return null }
        val targetId = best.targetEntityId ?: frame.passTargetId.takeIf {
            best.actionClass == ActionClass.PASS && best.targetX == frame.passTargetX && best.targetY == frame.passTargetY
        }
        val target = frame.entities.firstOrNull { it.id == targetId }
        val origin = frame.entities.firstOrNull { it.id == best.aimEntityId } ?: if (controlSlot == 0) mover else null
        val originVx = origin?.velocityXPerSecond ?: frame.ballVelocityXPerSecond
        val originVy = origin?.velocityYPerSecond ?: frame.ballVelocityYPerSecond
        val motion = com.assistant.execution.AimMotion(aimOriginX, aimOriginY, best.targetX, best.targetY,
            (target?.velocityXPerSecond ?: 0f) - originVx, (target?.velocityYPerSecond ?: 0f) - originVy,
            frame.predictedAtNanos, minOf(frameWidth(), frameHeight()) * 0.08f, frameWidth(), frameHeight())
        return ExecutionRequest(
            aimMotion = motion,
            actionClass = best.actionClass,
            source = ExecutionSource.SMART_ASSIST,
            phase = best.actionClass.ordinal,
            // no59 ITEM 1: carry the winning engine's name through the
            // terminal. Without this the dispatch log at route() could only
            // say SMART_ASSIST — the engine name lives on the contribution
            // (best.engine) and dies here if it is not copied onto the
            // request.
            engine = best.engine,
            // SPLENDOR_CONTROL_MAP_V1 - derive the joystick origin from the
            // trainer's live normalised control map instead of a fixed point.
            // All request coordinates live in the capture frame's space, so the
            // normalised centre is scaled by the capture dimensions.
            startX = startX,
            startY = startY,
            endX = leadX.coerceAtLeast(0f),
            endY = leadY.coerceAtLeast(0f),
            duration = (ControlMappingTrainer.personalDuration(best.actionClass, best.durationHintMs) * executionBias(best.actionClass)).toLong().coerceIn(15L, 85L)
        )
    }

    /*
     * PARKED-LANE WIRING (D11) — the trigger hook.
     *
     * Gates, cheapest first (onFrame runs per assembled frame; the trigger
     * itself is heavy — full legacy arbitration incl. native agility — and
     * routes a direct legacy request, so it must stay rare):
     *  1. Rate: at most one trigger per 250ms while the loop is idle.
     *  2. Service: globalInstance is null until the accessibility service
     *     connects (onServiceConnected sets it); skip silently until then.
     *  3. Threat: the nearest opponent must be closing on the joystick
     *     inside AutoEvade's threat radius (evadeTriggerPlausible — a
     *     superset filter; the monitor inside the trigger is the authority).
     *
     * Coordinates passed to the trigger mirror the legacy call's meaning:
     * start = joystick centre (same derivation chooseRequest uses for its
     * startX/startY), end = ball position (the carrier the vector acts on).
     *
     * Known consequences of firing (not hidden):
     *  - The trigger ALSO routes injectWinningVector's own SMART_ASSIST
     *    request straight to the bus, and revives AntiCutback's INT offer
     *    (emergency lane, direct route) — both ride the same call. On a
     *    trigger frame the bus can therefore receive two submissions (the
     *    legacy direct one, then the drained parked evade via chooseRequest
     *    below); both are short joystick drags from the same origin and the
     *    bus serialises them through the dispatch latch.
     *  - AutoEvade's 132ms cooldown never engages for parked offers
     *    (offer() returns false for non-emergency, so lastEvadeTimestamp is
     *    never set); re-parking while a threat persists relies on the
     *    registry's latest-per-(source, phase) replacement plus this
     *    hook's 250ms floor.
     *  - The trigger re-checks UiExecutionGate / enabled / foreground
     *    itself; onFrame's own gates above already passed, so this is
     *    defence-in-depth, not the live gate.
     */
    private fun maybeTriggerParkedLane(frame: RuntimeFrame) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastParkedTriggerMs < PARKED_TRIGGER_MIN_INTERVAL_MS) return
        val service = SmartAssistAccessibilityEngine.globalInstance ?: return
        val joystickX =
            com.assistant.vision.CameraProfile.captureWidthOrFallback() *
                ControlMappingTrainer.joystickCenterX()
        val joystickY =
            com.assistant.vision.CameraProfile.captureHeightOrFallback() *
                ControlMappingTrainer.joystickCenterY()
        val nearestOpp = frame.entities.filter { !it.teammate }.minByOrNull { e ->
            val dx = e.x - joystickX
            val dy = e.y - joystickY
            dx * dx + dy * dy
        } ?: return
        if (!evadeTriggerPlausible(
                joystickX, joystickY,
                nearestOpp.x, nearestOpp.y,
                frameHeight()
            )
        ) return
        lastParkedTriggerMs = now
        service.triggerInstantExecution(joystickX, joystickY, frame.ballX, frame.ballY)
    }

    /*
     * Threat pre-gate for the parked-lane trigger: a cheap mirror of
     * AutoEvadeEngine.monitorAttackingSpace's threat test, so the trigger
     * only fires when the monitor inside it plausibly would park.
     *
     * Velocity source matters: the monitor is fed
     * BallTrajectoryPredictor.current().velocityX/Y (px per FRAME —
     * MotionTracker.kt: ball.x - previousX, no dt division), NOT
     * frame.ballVelocityXPerSecond (px per SECOND). Reading the predictor
     * here keeps the gate's projection identical to the monitor's; using
     * the frame velocity would project the opponent ~fps-times too far.
     *
     * Superset radius: the monitor scales its 8.5%-of-height radius by
     * 1.0 + (|v| * 0.05 capped at 0.4); the gate uses the 1.4 maximum, so
     * it never rejects a threat the monitor would take. The constants
     * 0.085f / 1.4f are duplicated from AutoEvadeEngine (its consts are
     * private); the monitor stays the authority and this gate is only a
     * filter — a borderline miss re-evaluates on the next frame.
     */
    internal fun evadeTriggerPlausible(
        joystickX: Float,
        joystickY: Float,
        nearestOppX: Float,
        nearestOppY: Float,
        frameHeight: Float
    ): Boolean {
        val traj = BallTrajectoryPredictor.current()
        val predictionScale = (33.33f * 2f) / 1000f // 2 server ticks, as in AutoEvadeEngine
        val predictedOppX = nearestOppX + traj.velocityX * predictionScale
        val predictedOppY = nearestOppY + traj.velocityY * predictionScale
        val closing = hypot(predictedOppX - joystickX, predictedOppY - joystickY)
        return closing < frameHeight * 0.085f * 1.4f
    }

    /* f5: Pass/Shot/Cross threshold slider -> live duration factor. 50 = 1.0 (unchanged),
       0 = 0.0, 100 = 2.0. DEFEND/EVADE/other classes are not biased. */
    internal fun requiredControlSlot(contribution: EngineContribution): Int =
        if (contribution.controlSlot != 0) contribution.controlSlot else when (contribution.actionClass) {
            ActionClass.PASS, ActionClass.CROSS -> 3
            ActionClass.SHOT -> 2
            else -> 0
        }

    private fun frameWidth() = com.assistant.vision.CameraProfile.captureWidthOrFallback()
    private fun frameHeight() = com.assistant.vision.CameraProfile.captureHeightOrFallback()

    private fun executionBias(actionClass: ActionClass): Float {
        val cfg = SmartAssistRepository.configuration()
        val threshold = when (actionClass) {
            ActionClass.PASS -> cfg.passThreshold
            ActionClass.SHOT -> cfg.shotThreshold
            ActionClass.CROSS -> cfg.crossThreshold
            else -> 50
        }
        return threshold.coerceIn(0, 100) / 50f
    }

    private fun describe(best: EngineContribution?, parked: ExecutionRequest?): String =
        when {
            // no59 ITEM 1: a drained parked request now carries its
            // engine (offer sites set it); show the engine when claimed,
            // the source when not — never a guessed name.
            parked != null && best == null -> "parked:${parked.engine.ifBlank { parked.source.toString() }}"
            best != null -> "${best.engine}:${best.actionClass}"
            else -> "none"
        }

    fun reset() {
        lastCaptureId = -1L
        activeGestureEnvelope = null
        ActionOutcomeVerifier.reset()
        decisions.set(0L); routed.set(0L)
        FightingSpiritEngine.reset()
        CaptaincySkillEngine.reset()
        CrowdingZoneDetector.reset()
        lastParkedTriggerMs = 0L
        idleNoContribution.set(0L); idleUntrusted.set(0L)
        gateBlocked.set(0L); staleFrame.set(0L); settingsExit.set(0L); routeRejected.set(0L); chooseNull.set(0L)
        lastAction = "none"; lastWeight = 0f; lastUpdatedMs = 0L
    }

    fun decisionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "decisions" to decisions.get(),
        "routed" to routed.get(),
        "idleUntrusted" to idleUntrusted.get(),
        "idleNoContribution" to idleNoContribution.get(),
        "gateBlocked" to gateBlocked.get(),
        "staleFrame" to staleFrame.get(),
        "settingsExit" to settingsExit.get(),
        "routeRejected" to routeRejected.get(),
        "chooseNull" to chooseNull.get(),
        "lastAction" to lastAction,
        "lastWeight" to lastWeight,
        "lastUpdatedMs" to lastUpdatedMs,
        "mappingUiMode" to ControlMappingTrainer.uiMode.name,
        "mappingMatchActive" to ControlMappingTrainer.matchActive,
        "mappingTrainedSlots" to ControlMappingTrainer.trainedSlots()
    )
}

/* ========
RuntimeObservation
======== */
/**
 * Immutable runtime observation consumed by InAppAgentCore.
 *
 * This is deliberately a snapshot:
 * gameplay engines remain owners of gameplay state;
 * the agent only observes authoritative runtime surfaces.
 */
