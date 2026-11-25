package com.assistant
// F49_PATCH_V1

import android.os.SystemClock
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayEngineRegistry
import com.assistant.runtime.RuntimeFrame
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
    // N3 (f58): idleUntrusted used to merge two different gates (foreground/enabled
    // vs frame.trusted/opticalVariance) into one counter with no log line, so
    // the 2026-11-18 session's 877/947 could not be attributed to either.
    // Keep idleUntrusted as the sum for the existing snapshot/heal readers;
    // the split counters below are the attribution.
    private val idleUntrustedFg = AtomicLong(0L)
    private val idleUntrustedFrame = AtomicLong(0L)
    private val idleUntrustedVariance = AtomicLong(0L)
    // F37-D4/D6: the four loss exits below had no counter, so a DEC/ROUTED
    // gap could not be attributed. chooseNull splits the silent
    // chooseRequest() nulls out of idleNoContribution.
    private val gateBlocked = AtomicLong(0L)
    private val staleFrame = AtomicLong(0L)
    private val settingsExit = AtomicLong(0L)
    private val routeRejected = AtomicLong(0L)
    private val chooseNull = AtomicLong(0L)
    // GHOST-TOUCH (batch G): MOVE/EVADE holds dropped because the user
    // was not touching a control. The hold is counted here, not in
    // routeRejected: the bus never saw it, and the room's "Decision
    // Routed=" must stop climbing while the user is hands-off.
    private val moveHeldNoTouch = AtomicLong(0L)
    // JOYSTICK SLEW LIMITER (output stage) — state. ConAI is a stateless
    // per-frame max, so a winner flip swings the joystick direction
    // instantly. The limiter holds the previous smoothed direction and
    // the possession it was computed under; a possession flip resets it
    // so the stick snaps to the new situation instead of slewing
    // through the flip.
    @Volatile private var slewPrevDirX: Float = 0f
    @Volatile private var slewPrevDirY: Float = 0f
    @Volatile private var slewHasPrev: Boolean = false
    @Volatile private var slewPrevHasBall: Boolean = false
    @Volatile private var slewHasPrevBall: Boolean = false
    // USER-INTENT (UserIntentVision, Tier 2): requests held because
    // the user is holding that slot's control (see the gate below).
    private val intentHeld = AtomicLong(0L)
    // KNOB DIRECTION (JoystickKnobReader): requests held because they
    // oppose the user's held joystick direction (see the gate below).
    private val intentDirectionHeld = AtomicLong(0L)

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
            idleUntrustedFg.incrementAndGet()
            lastAction = "idle-untrusted-fg"
            return false
        }

        if (!frame.trusted) {
            idleUntrusted.incrementAndGet()
            idleUntrustedFrame.incrementAndGet()
            lastAction = "idle-untrusted-frame"
            return false
        }

        if (frame.opticalVariance >= MAX_OPTICAL_VARIANCE_FOR_ACTION) {
            idleUntrusted.incrementAndGet()
            idleUntrustedVariance.incrementAndGet()
            lastAction = "idle-untrusted-variance"
            return false
        }

        // CONTROL-MAPPING GATE: the mapping/settings screen must never
        // receive gameplay gestures; trained in-match cluster arms the stack.
        if (ControlMappingTrainer.uiMode == ControlMappingTrainer.UiMode.SETTINGS) {
            settingsExit.incrementAndGet(); com.assistant.execution.UiExecutionGate.block("control-calibration"); return false
        }
        ControlMappingTrainer.observeOutcome(frame)

        // D13: sanitize Ed's through press, if this frame carries one.
        // User-only: the gateway fires only on a fresh physical
        // press candidate + a viable through lane, and never initiates
        // a through pass on its own. Best-effort (never fails onFrame).
        try { ThroughPassSanitizerGateway.maybeSanitize(frame) } catch (_: Throwable) { }

        // SPLENDOR_AUTHORITY_REACHES_ARBITRATION_V1 - the master authority
        // slider used to reach RuntimeObservation only, so it could not change
        // what the engine actually did. Scale the weight every arbiter reads
        // (weight = authority * confidence) by the operator authority. At the
        // 100% default the factor is 1.0, so default behaviour is unchanged.
        val authorityFactor =
            RuntimePerformanceCoordinator.authority().coerceIn(0, 100) / 100f
        val collected = GameplayEngineRegistry.collect(frame).filter { c ->
            !ActionOutcomeVerifier.cooling(c.actionClass)
        }.map { c -> c.copy(confidence = c.confidence * ActionOutcomeVerifier.confidenceFactor(c.actionClass) * EngineCreditStore.credit(c.engine)) }.filter { c ->
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

        // Queue item 7 (F4): drain the parked lane LAZILY — only when the
        // fallback is actually needed (best == null). drainBest() used to
        // run on every frame and destroy the parked entry even when ConAI
        // had a best (chooseRequest ignores a parked entry unless best is
        // null), so a fallback offered on one frame never survived to a
        // frame that needed it, and a route rejection left nothing parked
        // for the next frame. Skipping the drain when best != null costs
        // nothing: parked is useless on those frames.
        val parked = if (best == null) ContributionRegistry.drainBest() else null

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

        // GHOST-TOUCH (batch G): a MOVE/EVADE request is a joystick
        // hold / escape drag. Routing it while the user is not touching
        // a control is the app moving the phone on its own — the loop
        // routes a MOVE hold on nearly every frame (MOVE is never
        // cooled), so hands-off this is a continuous autonomous drag.
        // Hold it: PASS/SHOT/CROSS/DEFEND/KEEPER are discrete/event
        // actions and are NOT gated here (the keeper must save while
        // the user is not touching). The touch gate is deliberately
        // NOT the bypass: bypass ON is Ed's working config, and it
        // must not re-open autonomous movement. isUserTouchAuthorizing()
        // (Phase 0 #2) expires shortly after lift and is withdrawn if
        // the touch flag sticks true without a fresh START, so a hold
        // extends briefly after release instead of cutting dead. Checked
        // BEFORE recordDispatch so a held request leaves no pending entry.
        if (HybridExecutionTerminal.isMovementHold(terminalRequest) && !UserTouchState.isUserTouchAuthorizing()) {
            moveHeldNoTouch.incrementAndGet()
            lastAction = "idle-no-touch-move"
            return false
        }

        // USER-INTENT (UserIntentVision, Tier 2): never fight a control
        // the user is holding. If this request's slot is in the frame's
        // hold mask, hold it — the user's own press/drag does that job
        // (a second assist gesture on the same control would double-fire
        // or steer against their drag). Slot map is requiredControlSlot's
        // (THROUGH=1, SHOT=2, PASS/CROSS/SWITCH=3, MOVE/EVADE=0 joystick);
        // DEFEND/KEEPER have no slot and are never held here (the keeper
        // must save while the user holds a button). CONSEQUENCE: a MOVE
        // is held whenever the joystick is held — the assist steers only
        // when the user's hands are off the stick (the ghost-touch gate
        // above already holds MOVE when not touching at all). The knob's
        // drag direction IS pixel-readable while held (JoystickKnobReader
        // — the finger never appears in a screen recording); the
        // direction gate below adds to this hold gate. Fail-open: mask 0
        // suppresses nothing.
        // Checked BEFORE recordDispatch so a held request leaves no
        // pending entry.
        val requestSlot = slotForRequest(terminalRequest.actionClass)
        if (requestSlot >= 0 && frame.userIntentMask and UserIntentVision.slotBit(requestSlot) != 0) {
            intentHeld.incrementAndGet()
            lastAction = "idle-intent-held"
            return false
        }

        // KNOB DIRECTION (JoystickKnobReader): never dispatch AGAINST
        // the user's held direction. This gate is ADDITIVE — the hold
        // gate above stays exactly as it is; this one only adds
        // suppression, never removes it. It fires where the hold gate
        // cannot: a confidently segmented deflected knob suppresses an
        // opposing joystick request on a single frame, without waiting
        // for the hold's 2-frame confirmation and without the touch
        // flag. Slot 0 only (MOVE/EVADE — the joystick-driven
        // requests): a button request's swipe direction is a flick from
        // a button centre, not a joystick direction, so the knob says
        // nothing about it. Opposing = dot < 0 (angle > 90°);
        // perpendicular (dot = 0) is not opposing. Fail-open: null
        // direction, or magnitude below the reader's deadzone,
        // suppresses nothing. Checked BEFORE recordDispatch so a held
        // request leaves no pending entry.
        if (requestSlot == 0) {
            val userDir = frame.userIntentDirection
            if (userDir != null && userDir.magnitude >= com.assistant.overlay.interceptor.JoystickKnobReader.MIN_DIRECTION_MAGNITUDE) {
                val rdx = terminalRequest.endX - terminalRequest.startX
                val rdy = terminalRequest.endY - terminalRequest.startY
                val rlen = hypot(rdx, rdy)
                if (rlen > 0f) {
                    val dot = (rdx / rlen) * userDir.dirX + (rdy / rlen) * userDir.dirY
                    if (dot < 0f) {
                        intentDirectionHeld.incrementAndGet()
                        lastAction = "idle-intent-opposed"
                        return false
                    }
                }
            }
        }

        // SHOT AIM STEER (shot core fix, item 1): pre-steer the stick
        // toward the shot's aim before the shoot tap lands — see
        // routeShotAimSteer. The MOVE is routed immediately before the
        // SHOT in this same frame; the bus consumes FIFO (MOVE first,
        // SHOT second — different (source, phase) reservations, so no
        // collision), and the DIRECT_DISPATCH lines show the MOVE to
        // the aim direction immediately before each SHOT dispatch.
        if (terminalRequest.actionClass == ActionClass.SHOT && best != null) {
            routeShotAimSteer(best, terminalRequest, frame)
        }

        // Register before publishing: the bus consumer runs on another thread.
        ActionOutcomeVerifier.recordDispatch(terminalRequest, frame)
        val accepted = HybridExecutionTerminal.route(terminalRequest)
        // G2 (item 20): timing-feedback submit leg — only when the bus accepts.
        if (accepted) GestureTimingFeedbackEngine.recordSubmission(terminalRequest.actionId)
        if (!accepted) {
            routeRejected.incrementAndGet()
            ActionOutcomeVerifier.cancelDispatch(terminalRequest.actionId)
            // L5 / Q68-55 (batch 1): the decision-error hook, wired at the
            // dispatch site — a route rejection is a decision that failed
            // to execute. (SmartAssistMetrics.noteDecisionError had zero
            // callers before this round.)
            SmartAssistMetrics.noteDecisionError()
        }
        if (accepted) {
            routed.incrementAndGet()
            // Queue item 13: the route-accept recordDispatch call is
            // DELETED — the end-to-end leg now records once per executed
            // gesture at the consume site (SmartAssistAccessibilityEngine
            // .executeDirectRequest), which covers every dispatch path
            // (RDL's own accepted branch executed zero times in v220,
            // so the leg never recorded). See PipelineLatencyMonitor.
            // L3 (batch 1): density telemetry at the dispatch decision.
            DensityTelemetryLogger.logDensity(
                frame.defenderDensity,
                terminalRequest.actionClass?.name ?: "NONE"
            )
            // L5 / Q68-55 (batch 1): the action-triggered hook, wired at
            // the dispatch site. (noteActionTriggered had zero callers
            // before this round; the snapshot published permanent 0s.)
            SmartAssistMetrics.noteActionTriggered()
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

    // ── Output stage: AnalogDeflection + JoystickSlewLimiter ─────────
    // ANALOG DEFLECTION: the contributor's intended travel distance
    // (aim origin → target, capture-frame px) sets the joystick
    // deflection radius, bounded by the MEASURED max drag. Falls back
    // to the legacy 0.08f factor only while the trainer probe has not
    // measured a radius on this device (maxDragPx == null). Never
    // invents a radius: the ceiling is the probe's measured
    // DispatchBounds.maxDragPx (file comment: "NOTHING consumes it
    // yet" — this is the queued follow-up that wires it in).
    // A short intent (small distance) deflects gently (fine dribble);
    // a long intent clamps to the ceiling (full stick, sprint).
    private fun analogDeflectionRadius(distance: Float): Float {
        val measuredMax = com.assistant.execution.DispatchBounds.maxDragPx
        val ceiling = measuredMax ?: (minOf(frameWidth(), frameHeight()) * 0.08f)
        return distance.coerceIn(0f, ceiling)
    }

    // SLEW LIMITER tuning (design parameters for this new engine; the
    // deflection ceiling above is the only measured value in this
    // stage): deadband 5° (ignore sub-5° jitter), max 45°/frame settled
    // (a full 180° reversal takes ~8 frames / ~400 ms at the 20 fps
    // processed rate — the clamp alone would allow 4 frames / 200 ms,
    // but the EMA halves the effective swing), EMA alpha 0.5 (halve
    // the remaining angle each frame). The slew clamp is an upper
    // bound — the EMA makes the effective per-frame swing smaller,
    // which is the safe direction.
    //
    // URGENCY-AWARE SLEW (step 4, 2026-11-24): a reactive winner turns
    // the stick twice as fast. The effective swing per frame is
    // ~clamp/2 (EMA alpha 0.5 halves the clamped swing), so a 180°
    // reversal takes 180/(90/2) = 4 frames / 200 ms at the urgent
    // 90° clamp vs 180/(45/2) = 8 frames / 400 ms at the settled
    // 45° clamp (20 fps processed rate). 90° — not a bypass — keeps
    // the turn slewed: the stick still cannot snap in one frame, and
    // the EMA still smooths every step. INVARIANT: the clamp only
    // ever WIDENS with urgency, never narrows below today's 45° —
    // an unknown or missing urgency signal falls back to the settled
    // clamp (fail-open; see isUrgentTurn).
    private const val SLEW_DEADBAND_DEG = 5f
    private const val SLEW_MAX_DEG_PER_FRAME = 45f
    private const val SLEW_MAX_DEG_PER_FRAME_URGENT = 90f
    private const val SLEW_EMA_ALPHA = 0.5f

    // Urgency signal for the slew limiter: the winning contribution's
    // action class (typed enum, read at the chooseRequest call site
    // where `best` is in scope). KEEPER = keeper save/rush — the
    // panic save itself is a KEEPER move (PanicSaveContributor);
    // DEFEND = interception and defensive engagement (InstantIntercept
    // :164/:170, InterceptMatrix, ShotAnticipation :22, Defense
    // Contributor — all DEFEND); EVADE = dodging an opponent
    // (EvadeContributor); SWITCH = the cursor-switch flick (Phase 5,
    // step 2) — its flick direction selects the player, so a tight
    // clamp would bend the flick toward the wrong player. All four
    // are reactive: the situation changed under the player, so the
    // stick must catch up. MOVE / PASS / SHOT / CROSS / THROUGH /
    // NONE are settled-possession moves and keep the 45° clamp.
    // REJECTED signals: frame.panic (RuntimeFrame.kt:93) is the
    // user's panic-mode toggle (FrameAssembler.kt:211,
    // SmartAssistRepository.panicActive()), a global setting, not
    // per-request urgency; best.weight (authority x confidence) is
    // arbitration strength, not urgency — a settled MOVE can win at
    // 0.95; best.engine is a free-form string ("PanicSave",
    // "InstantIntercept") — fragile matching for what the typed
    // action class already says. A future action class lands in
    // `else` and keeps the settled clamp.
    private fun isUrgentTurn(actionClass: ActionClass): Boolean = when (actionClass) {
        ActionClass.KEEPER, ActionClass.DEFEND, ActionClass.EVADE, ActionClass.SWITCH -> true
        else -> false
    }

    // Returns the slew-limited unit direction for this frame.
    // newDirX/newDirY must already be a unit vector. A possession flip
    // resets the limiter so the stick snaps to the new situation
    // instead of slewing through the flip. `urgent` widens the
    // per-frame clamp (90° vs 45°) — it never narrows it; false keeps
    // exactly today's behaviour.
    private fun slewLimitDirection(newDirX: Float, newDirY: Float, hasBall: Boolean, urgent: Boolean = false): Pair<Float, Float> {
        if (slewHasPrevBall && slewPrevHasBall != hasBall) slewHasPrev = false
        slewPrevHasBall = hasBall
        slewHasPrevBall = true
        if (!slewHasPrev) {
            slewPrevDirX = newDirX
            slewPrevDirY = newDirY
            slewHasPrev = true
            return newDirX to newDirY
        }
        val prevX = slewPrevDirX
        val prevY = slewPrevDirY
        val dot = (prevX * newDirX + prevY * newDirY).coerceIn(-1f, 1f)
        val angleDeg = Math.toDegrees(acos(dot.toDouble())).toFloat()
        // Deadband: a sub-deadband change is jitter, not intent — hold
        // the previous direction. Drift accumulates against the held
        // direction, so a slow real turn still breaks through once the
        // accumulated angle exceeds the deadband.
        if (angleDeg < SLEW_DEADBAND_DEG) return prevX to prevY
        // Slew clamp: rotate the previous direction toward the new one
        // by at most the per-frame maximum — 90° on an urgent turn,
        // 45° settled. The wide value is never below the settled one.
        val maxDeg = if (urgent) SLEW_MAX_DEG_PER_FRAME_URGENT else SLEW_MAX_DEG_PER_FRAME
        val clamped = rotateToward(prevX, prevY, newDirX, newDirY, maxDeg)
        // EMA: blend the previous direction toward the clamped target.
        val bx = prevX + (clamped.first - prevX) * SLEW_EMA_ALPHA
        val by = prevY + (clamped.second - prevY) * SLEW_EMA_ALPHA
        val blen = hypot(bx, by)
        val outX = if (blen > 0f) bx / blen else newDirX
        val outY = if (blen > 0f) by / blen else newDirY
        slewPrevDirX = outX
        slewPrevDirY = outY
        return outX to outY
    }

    // Rotate unit vector (fromX, fromY) toward unit vector (toX, toY)
    // by at most maxDeg degrees. Returns a unit vector.
    private fun rotateToward(fromX: Float, fromY: Float, toX: Float, toY: Float, maxDeg: Float): Pair<Float, Float> {
        val dot = (fromX * toX + fromY * toY).coerceIn(-1f, 1f)
        val angleDeg = Math.toDegrees(acos(dot.toDouble())).toFloat()
        if (angleDeg <= maxDeg || angleDeg <= 0f) return toX to toY
        val cross = fromX * toY - fromY * toX
        val sign = if (cross >= 0f) 1f else -1f
        val theta = Math.toRadians(maxDeg.toDouble()).toFloat() * sign
        val c = cos(theta)
        val s = sin(theta)
        val rx = fromX * c - fromY * s
        val ry = fromX * s + fromY * c
        val rlen = hypot(rx, ry)
        return if (rlen > 0f) (rx / rlen) to (ry / rlen) else (toX to toY)
    }

    // SHOT AIM STEER (shot core fix, item 1): the game's real aim input is
    // the left stick, and a SHOT routes only a shoot-button tap — the stick
    // stays wherever the last MOVE left it (or centred), so the shot goes
    // in the stick's direction, not toward the app's far-corner target.
    // When a SHOT wins, pre-steer the stick toward the same ball→target
    // direction (a MOVE hold through the same slew + deflection path) and
    // hold it through the shoot tap: the MOVE is routed immediately
    // before the SHOT in this same frame, and the bus consumes in FIFO
    // order (MOVE first, SHOT second — different (source, phase)
    // reservations, so no collision). The pre-steer is a movement hold,
    // so it obeys the same two gates as any MOVE — the ghost-touch gate
    // (no autonomous stick while Ed is not touching) and the never-fight
    // gate (never steer against Ed's own drag on the joystick). When
    // either gate holds it, the shot fires unsteered, exactly as today
    // (fail-open). The MOVE is an auxiliary steering gesture, not a
    // decision, so it is routed through the terminal (clamp + dedup +
    // dispatch line) but not recorded with ActionOutcomeVerifier; the
    // DIRECT_DISPATCH line is its record — a MOVE to the aim direction
    // immediately before each SHOT dispatch is the falsifier.
    private fun routeShotAimSteer(best: EngineContribution, shotRequest: ExecutionRequest, frame: RuntimeFrame) {
        if (!UserTouchState.isUserTouchAuthorizing()) return
        if (frame.userIntentMask and UserIntentVision.slotBit(0) != 0) return
        val originX = best.aimOriginX ?: frame.ballX
        val originY = best.aimOriginY ?: frame.ballY
        val dx = best.targetX - originX
        val dy = best.targetY - originY
        val distance = hypot(dx, dy)
        if (!distance.isFinite() || distance <= 0f) return
        val dirX = dx / distance
        val dirY = dy / distance
        val (slewX, slewY) = slewLimitDirection(dirX, dirY, frame.hasBall, isUrgentTurn(best.actionClass))
        val deflection = analogDeflectionRadius(distance)
        val joyX = frameWidth() * ControlMappingTrainer.joystickCenterX()
        val joyY = frameHeight() * ControlMappingTrainer.joystickCenterY()
        val move = ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = ActionClass.MOVE.ordinal,
            startX = joyX,
            startY = joyY,
            endX = (joyX + slewX * deflection).coerceIn(0f, frameWidth() - 1f),
            endY = (joyY + slewY * deflection).coerceIn(0f, frameHeight() - 1f),
            duration = shotRequest.duration,
            gestureEnvelope = shotRequest.gestureEnvelope,
            aimMotion = null,
            actionClass = ActionClass.MOVE,
            engine = best.engine
        )
        HybridExecutionTerminal.route(move)
    }

    private fun chooseRequest(
        best: EngineContribution?,
        parked: ExecutionRequest?,
        frame: RuntimeFrame
    ): ExecutionRequest? {
        // SWITCH (Phase 5, step 2): a switch request is only
        // meaningful when the game cursor is known — the switch is
        // FROM the cursor player TOWARD the target point. frame.cursorId
        // is null when no ring was seen this frame (or the ring was ambiguous); without it there is no "from" to
        // switch away from, so the request dies here. Placed BEFORE
        // the parked return so the parked lane cannot smuggle a
        // cursor-less switch through: the effective class is the
        // parked entry's when best is null, best's otherwise.
        // Fail-open: every other class is untouched, and a SWITCH
        // with a known cursor passes.
        if ((parked?.actionClass == ActionClass.SWITCH || best?.actionClass == ActionClass.SWITCH) && frame.cursorId == null) {
            chooseNull.incrementAndGet()
            return null
        }
        // D-23-02/D3: the drained entry is a PARKED contribution (the only
        // source that parks is SMART_ASSIST), not an emergency - emergency
        // sources (GK/INT) bypass the map entirely. The old code compared
        // its priority against SMART_ASSIST's (90 > 90), which could never
        // be true, so the branch was dead and the drained entry was
        // silently dropped whenever a ConAI best existed. Precedence is
        // now explicit: a parked entry is the fallback when arbitration
        // has no best; it never outranks a live best (bus rings already
        // give GK/INT priority over SMART_ASSIST).
        if (parked != null && best == null) {
            return parked
        }
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
        // Deflection radius for this request. Default is the legacy fixed
        // factor; the aim-origin branch below overwrites it with the
        // intent-scaled radius. Hoisted so AimMotion (below) uses the
        // same radius as the direct endpoint — the compensation path
        // must not recompute a different deflection.
        var deflectionRadius = minOf(frameWidth(), frameHeight()) * 0.08f
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
            // OUTPUT STAGE — slew then deflect, in this order:
            // 1. SlewLimiter smooths the winner's direction (unit vector).
            // 2. AnalogDeflection scales the radius by contributor intent
            //    (distance), bounded by the measured max drag.
            val dirX = dx / distance
            val dirY = dy / distance
            val (slewX, slewY) = slewLimitDirection(dirX, dirY, frame.hasBall, isUrgentTurn(best.actionClass))
            deflectionRadius = analogDeflectionRadius(distance)
            leadX = (startX + slewX * deflectionRadius).coerceIn(0f, frameWidth() - 1f)
            leadY = (startY + slewY * deflectionRadius).coerceIn(0f, frameHeight() - 1f)
        }
        if (aimOriginX == null || aimOriginY == null) { chooseNull.incrementAndGet(); return null }
        val targetId = best.targetEntityId ?: frame.passTargetId.takeIf {
            best.actionClass == ActionClass.PASS && best.targetX == frame.passTargetX && best.targetY == frame.passTargetY
        }
        val target = frame.entities.firstOrNull { it.id == targetId }
        val origin = frame.entities.firstOrNull { it.id == best.aimEntityId } ?: if (controlSlot == 0) mover else null
        val originVx = origin?.velocityXPerSecond ?: frame.ballVelocityXPerSecond
        val originVy = origin?.velocityYPerSecond ?: frame.ballVelocityYPerSecond
        // LatencyBudgetLead: pass the measured forward latency
        // (dispatch → on-screen effect) into AimMotion so endpoint()
        // leads by prediction age PLUS real pipeline delay. The monitor
        // returns -1 when no effect has been confirmed in the window —
        // that contributes zero (age term alone, as before). The read is
        // a single synchronized Long, no allocation.
        val forwardLatencyNanos = PipelineLatencyMonitor.effectMeanMs().let {
            if (it < 0L) 0L else it * 1_000_000L
        }
        val motion = com.assistant.execution.AimMotion(aimOriginX, aimOriginY, best.targetX, best.targetY,
            (target?.velocityXPerSecond ?: 0f) - originVx, (target?.velocityYPerSecond ?: 0f) - originVy,
            frame.predictedAtNanos, deflectionRadius, frameWidth(), frameHeight(),
            forwardLatencyNanos = forwardLatencyNanos)
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
            duration = run {
                val base = (ControlMappingTrainer.personalDuration(best.actionClass, best.durationHintMs) * executionBias(best.actionClass)).toLong().coerceIn(15L, 85L)
                // G-M (batch 4, Q68-31): a barge contribution renders as a
                // SHORT, HARD swipe — duration clamped to <= 20 ms. The
                // barge flag + this clamp ARE the terminal support for
                // the barge channel; the terminal dispatches swipes, and
                // a barge is a short hard swipe, not an ordinary DEFEND
                // drag. A non-barge contribution is unchanged.
                if (best.barge) base.coerceAtMost(20L) else base
            }
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
     *  - AutoEvade's 132ms cooldown DOES gate re-parking: the timestamp
     *    IS set on the park (AutoEvadeEngine stamps lastEvadeTimestamp
     *    when it parks, No67-D3 — offer() returns false for non-emergency,
     *    so the park, not a success, is what stamps it). Re-parking while
     *    a threat persists is throttled by that cooldown plus the
     *    registry's latest-per-(source, phase) replacement and this
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
     * here keeps the gate on the same velocity the monitor sees; using
     * the frame velocity would project the opponent ~fps-times too far.
     *
     * Projection (queue item 6): the gate projects the same 2 frames as
     * the monitor — `traj.velocityX * 2f`. At the 20 fps processed rate
     * that is 100 ms, a superset of the monitor's 66.7 ms wall-time
     * horizon, so the gate never rejects a threat the monitor would
     * take. The old 0.0667 scale under-projected 30x: it treated a
     * px/frame velocity as px/second.
     *
     * Superset radius: the monitor scales its 8.5%-of-height radius by
     * 1.0 + (|v| * 0.0025 capped at 0.4) on per-second magnitudes; the gate uses the 1.4 maximum, so
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
        val predictedOppX = nearestOppX + traj.velocityX * 2f
        val predictedOppY = nearestOppY + traj.velocityY * 2f
        val closing = hypot(predictedOppX - joystickX, predictedOppY - joystickY)
        return closing < frameHeight * 0.085f * 1.4f
    }

    /* f5: Pass/Shot/Cross threshold slider -> live duration factor. 50 = 1.0 (unchanged),
       0 = 0.0, 100 = 2.0. DEFEND/EVADE/other classes are not biased. */
    internal fun requiredControlSlot(contribution: EngineContribution): Int =
        if (contribution.controlSlot != 0) contribution.controlSlot else when (contribution.actionClass) {
            ActionClass.PASS, ActionClass.CROSS -> 3
            ActionClass.SHOT -> 2
            // D13: a through pass is pressed on slot 1 (Through). A
            // THROUGH contribution therefore routes from the Through
            // button and is gated on slot 1 being trained, like every
            // other slot-routed action.
            ActionClass.THROUGH -> 1
            // SWITCH (Phase 5, step 2): the cursor-switch flick is
            // pressed on slot 3 (Switch/Pass) — the same physical
            // button as PASS, a new request type, never a repurposed
            // PASS. Gated on slot 3 trained, like every other
            // slot-routed action.
            ActionClass.SWITCH -> 3
            else -> 0
        }

    /*
     * UserIntentVision slot for a routed request's action class, or -1
     * when the class has no user control (DEFEND/KEEPER/NONE). Mirrors
     * requiredControlSlot's mapping; MOVE/EVADE are the joystick (0);
     * SWITCH is slot 3, so a switch request is held while the user
     * holds the Switch button — the user's own switch press wins.
     */
    private fun slotForRequest(actionClass: ActionClass?): Int = when (actionClass) {
        ActionClass.THROUGH -> 1
        ActionClass.SHOT -> 2
        ActionClass.PASS, ActionClass.CROSS, ActionClass.SWITCH -> 3
        ActionClass.MOVE, ActionClass.EVADE -> 0
        else -> -1
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
        slewPrevDirX = 0f; slewPrevDirY = 0f; slewHasPrev = false
        slewPrevHasBall = false; slewHasPrevBall = false
        ActionOutcomeVerifier.reset()
        decisions.set(0L); routed.set(0L)
        CaptaincySkillEngine.reset()
        CrowdingZoneDetector.reset()
        lastParkedTriggerMs = 0L
        idleNoContribution.set(0L); idleUntrusted.set(0L)
        idleUntrustedFg.set(0L); idleUntrustedFrame.set(0L); idleUntrustedVariance.set(0L)
        gateBlocked.set(0L); staleFrame.set(0L); settingsExit.set(0L); routeRejected.set(0L); chooseNull.set(0L)
        moveHeldNoTouch.set(0L); intentHeld.set(0L)
        lastAction = "none"; lastWeight = 0f; lastUpdatedMs = 0L
    }

    fun decisionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "decisions" to decisions.get(),
        "routed" to routed.get(),
        "idleUntrusted" to idleUntrusted.get(),
        "idleUntrustedFg" to idleUntrustedFg.get(),
        "idleUntrustedFrame" to idleUntrustedFrame.get(),
        "idleUntrustedVariance" to idleUntrustedVariance.get(),
        "idleNoContribution" to idleNoContribution.get(),
        "gateBlocked" to gateBlocked.get(),
        "staleFrame" to staleFrame.get(),
        "settingsExit" to settingsExit.get(),
        "routeRejected" to routeRejected.get(),
        "chooseNull" to chooseNull.get(),
        "moveHeldNoTouch" to moveHeldNoTouch.get(),
        "intentHeld" to intentHeld.get(),
        "lastAction" to lastAction,
        "lastWeight" to lastWeight,
        "lastUpdatedMs" to lastUpdatedMs,
        "mappingUiMode" to ControlMappingTrainer.uiMode.name,
        "mappingMatchActive" to ControlMappingTrainer.matchActive,
        "mappingTrainedSlots" to ControlMappingTrainer.trainedSlots()
    )
}
