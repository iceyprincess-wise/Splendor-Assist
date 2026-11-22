package com.assistant

import com.assistant.runtime.*

import java.util.Locale

import kotlin.math.*

object MagneticFeetContributor : GameplayContributor {

    override val engineName: String =
        "MagneticFeet"

    override val capabilities: Set<EngineCapability> =
        setOf(
            EngineCapability.MOVEMENT,
            EngineCapability.ATTACK
        )

    /*
     * Vision coordinates are used here, not Unreal Engine memory units.
     * This is the live screen-space magnetic envelope.
     */
    private const val MAGNETIC_RADIUS = 250.0f

    private const val MIN_DISTANCE = 1.0f

    /*
     * No68-B-D18: there is no joystick origin in this file anymore.
     * The target is a VISION-SPACE point on the owner->ball ray (the
     * convention every other MOVE contributor uses): chooseRequest()
     * derives the joystick deflection as (target - aimOrigin), so a
     * joystick-space target here skewed the direction by
     * (joystickOrigin - owner) and could drive the carrier AWAY from
     * the ball. The aim origin is the owner itself (see contribute()).
     */

    private const val MIN_TRAVEL = 45.0f
    private const val MAX_TRAVEL = 180.0f

    private val SCREEN_MAX_X get() = com.assistant.vision.CameraProfile.captureWidthOrFallback()
    private val SCREEN_MAX_Y get() = com.assistant.vision.CameraProfile.captureHeightOrFallback()

    /*
     * Same live floor used by FrameAssembler for possession confidence.
     */
    private const val POSSESSION_MIN_CONFIDENCE = 0.20f

    // SPLENDOR_P10_TRAP_STATE: last trap window, for the window-enter
    // TRAP log (one line per CLOSING entry, not per frame).
    @Volatile
    private var prevTrapWindow = "none"

    // No68-B-D17: the base-class fastGate does not apply here -- this
    // contributor implements GameplayContributor directly, so the
    // default fastGate was `true` and the full contribute() body ran
    // on panic frames. Mirror the ObservedGameplayEngines base gate
    // (trusted, not panicking, ball in play); the registry already
    // checks trusted, but panic is only checked here.
    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.panic && frame.hasBall

    override fun initialize() {
        MagneticFeetEngine.reset()
    }

    override fun contribute(
        frame: RuntimeFrame
    ): EngineContribution? {

        /*
         * Use the same runtime truth gates as the rest of the gameplay
         * contributor architecture.
         */
        if (
            !frame.enabled ||
            !frame.trusted ||
            !frame.hasBall
        ) {
            return null
        }

        val worldState =
            try {
                Phase3WorldStateStore.current()
            } catch (_: Throwable) {
                return null
            }

        val possession =
            worldState.possession

        if (
            !possession.hasPossession ||
            possession.confidence <
                POSSESSION_MIN_CONFIDENCE ||
            possession.ownerIndex < 0
        ) {
            return null
        }

        val scene =
            try {
                SceneTracker.current()
            } catch (_: Throwable) {
                return null
            }

        /*
         * BallOwnershipEngine's ownerIndex points into the same
         * SceneTracker snapshot that produced the possession result.
         */
        val owner =
            scene.trackedPlayers.getOrNull(
                possession.ownerIndex
            ) ?: return null

        /*
         * Magnetic Feet is an attacking ball-retention movement assist.
         * Do not drive the goalkeeper or an opponent.
         */
        if (
            !owner.isUserTeam ||
            owner.isGoalkeeper ||
            owner.confidence <= 0.0f
        ) {
            return null
        }

        val ballX =
            frame.ballX

        val ballY =
            frame.ballY

        if (
            !ballX.isFinite() ||
            !ballY.isFinite() ||
            !owner.x.isFinite() ||
            !owner.y.isFinite()
        ) {
            return null
        }

        /*
         * Direction from the tracked ball carrier toward the live ball.
         */
        val deltaX =
            ballX - owner.x

        val deltaY =
            ballY - owner.y

        val distance =
            hypot(
                deltaX.toDouble(),
                deltaY.toDouble()
            ).toFloat()

        if (
            !distance.isFinite() ||
            distance < MIN_DISTANCE ||
            distance > MAGNETIC_RADIUS
        ) {
            return null
        }

        /*
         * SPLENDOR_P10_TRAP_GATE: first-touch gate. Ball velocity toward the
         * owner (closing speed) plus nearest-defender distance, in
         * px/frame and px throughout. frame.ballVelocityX/Y are per-frame
         * deltas (BallTelemetryBridge publishes x - previousX, no time
         * division), so they are NEVER multiplied by seconds here.
         * Nearest-defender distance is derived from frame.entities --
         * TelemetrySnapshot.opponentDistance is a goalkeeper pixel-scan
         * value, not a distance to the owner, and never reaches
         * RuntimeFrame. Bands reuse repo constants only: 5 px/frame
         * intercept floor (ObservedGameplayEngines), 18-38 px sweet spot
         * (SharpTouchTimingEngine), 15/45 px Dreadnought bands, 0..6
         * frame intercept horizon.
         */
        val trapUx = deltaX / distance
        val trapUy = deltaY / distance
        val closingSpeed = frame.ballVelocityX * trapUx + frame.ballVelocityY * trapUy
        val trapBallSpeed = hypot(frame.ballVelocityX.toDouble(), frame.ballVelocityY.toDouble()).toFloat()
        val nearestDefenderDist = frame.entities.filter { !it.teammate }
            .minOfOrNull { e -> hypot((e.x - owner.x).toDouble(), (e.y - owner.y).toDouble()).toFloat() }
            ?: Float.MAX_VALUE
        val trapMargin = 5f * (1f - frame.confidence.coerceIn(0f, 1f))
        // No68-B-D24: the band is computed once and the weight derived
        // from it -- the old code evaluated the same two thresholds in
        // two separate `when` blocks, which could drift apart.
        val trapBand = when {
            nearestDefenderDist <= 15f + trapMargin -> "HOT"
            nearestDefenderDist <= 45f + trapMargin -> "PRESS"
            else -> "LOOSE"
        }
        val trapBandWeight = when (trapBand) {
            "HOT" -> 1.0f
            "PRESS" -> 0.6f
            else -> 0.25f
        }
        val trapWindow = when {
            closingSpeed >= 5f -> "CLOSING"
            trapBallSpeed < 5f -> "SETTLED"
            else -> "LEAVING"
        }
        // No68-B-D21: (distance - 18f) goes negative once the ball is
        // already inside the 18 px sweet spot, so the most urgent balls
        // -- closing fast at close range -- scored a negative time and
        // missed the boost window entirely. Clamp at zero: already
        // inside the sweet spot means contact is now.
        val timeToContactF = if (closingSpeed > 0f) maxOf(0f, (distance - 18f) / closingSpeed) else Float.POSITIVE_INFINITY
        val trapBoost = if (closingSpeed >= 5f && timeToContactF in 0f..6f) 1.0f + 0.25f * trapBandWeight else 1.0f
        val prePosition = closingSpeed >= 5f && timeToContactF > 6f
        val enteredTrapWindow = trapWindow == "CLOSING" && prevTrapWindow != "CLOSING"
        prevTrapWindow = trapWindow
        MagneticFeetEngine.recordTrapWindow(trapWindow)
        if (trapWindow == "LEAVING") return null

        /*
         * Stronger magnetic behavior is associated with a closer ball.
         */
        val proximity =
            (
                1.0f -
                    (
                        distance /
                            MAGNETIC_RADIUS
                    )
            ).coerceIn(
                0.0f,
                1.0f
            )

        /*
         * No68-B-D33: this is a proximity signal (1.0 = ball on the
         * owner's feet), not defender pressure -- the old name
         * `pressure` hid that, and ActiveGestureController feeds the
         * same engine parameter with raw ball distance, i.e. the
         * opposite sense (far ball = larger input). The parameter is
         * renamed in MagneticFeetEngine.stabilize() to match; the AGC
         * call site is a queue item (QB-3), not this file.
         */
        val proximitySignal =
            (
                proximity *
                    100.0f
            ).toInt().coerceIn(
                0,
                100
            )

        /*
         * Strength is based on actual tracked-player confidence and
         * frame confidence, not passing-lane confidence.
         */
        val normalizedStrength =
            (
                (owner.confidence * 0.70f) +
                    (frame.confidence * 0.30f)
            ).coerceIn(
                0.0f,
                1.0f
            )

        val strength =
            (
                normalizedStrength *
                    100.0f
            ).toInt().coerceIn(
                0,
                100
            )

        val result =
            MagneticFeetEngine.stabilize(
                proximity = proximitySignal,
                strength = strength
            )

        /*
         * Requested upgrade constants retained.
         */
        val possessionWeight =
            1.5f

        val trustWeight =
            1.2f

        val cap =
            1.2f

        val amplification =
            1.2f

        val fluidMultiplier =
            (
                possessionWeight +
                    trustWeight
            ) / 2.0f

        /*
         * Exact requested raw-authority formula.
         */
        val rawAuthority =
            (
                result.touchRetention /
                    5.0f
            ) * amplification

        val weightedAuthority =
            rawAuthority *
                fluidMultiplier

        /*
         * No68-B-D20: shape the weighted authority before the requested
         * 0.9..1.2 bounds so the result does not immediately hit the cap
         * for every moderately high touch value. The old bend
         * w / (1 + 0.20w) still saturated: touch >= 3.86 already gave
         * authority 1.0, and the 60 px ownership gate holds steady-state
         * touch >= 5.24, so authority was 1.0 on EVERY steady-state
         * frame and the trap boost was invisible -- ConAI ranked this
         * engine by confidence alone. w / (1 + w) keeps the same
         * requested constants and monotonicity but never saturates to
         * the cap (touch 12 -> 0.795, x1.25 boost -> 0.994).
         */
        val shapedAuthority =
            weightedAuthority /
                (
                    1.0f +
                        weightedAuthority
                )

        /*
         * Final confidence is based only on live runtime evidence.
         */
        val confidence =
            (
                (frame.confidence * 0.30f) +
                    (possession.confidence * 0.35f) +
                    (owner.confidence * 0.20f) +
                    (proximity * 0.15f)
            ).coerceIn(
                0.0f,
                1.0f
            )

        /*
         * EngineContribution.weight clamps authority to 0..1.
         * Keep the requested 1.2 internal cap but do not pretend values above
         * one survive the runtime contract.
         */
        // SPLENDOR_P9_MF_AUTHORITY: the old 0.9f floor forced authority
        // into [0.9, 1.0] on every contribution, so arbitration could
        // never rank it -- fabricated inflation. Floor 0.0f: authority
        // now reflects live proximity/confidence.
        // No68-B-D25: one clamp. EngineContribution.weight clamps
        // authority to 0..1 anyway, so the requested 1.2 internal cap
        // survives only as minOf(cap, 1.0f) -- the old double coerceIn
        // said the same thing twice.
        val authority =
            (shapedAuthority * trapBoost)
                .coerceIn(
                    0.0f,
                    minOf(cap, 1.0f)
                )

        /*
         * Vision-space target on the owner->ball ray: the owner plus
         * the unit direction times the travel. chooseRequest() derives
         * the joystick deflection as (target - aimOrigin), with
         * aimOrigin = owner (set on the contribution below), so the
         * direction that reaches the screen is exactly directionX/Y.
         * (No68-B-D18: the old target was JOYSTICK_ORIGIN + dir*travel
         * while the aim origin was the owner, so the deflection was
         * (joystickOrigin - owner) + dir*travel -- for an owner at
         * (1200,300) and a ball to his right the stick pushed LEFT.)
         * Travel magnitude itself does not survive chooseRequest() --
         * it normalises the vector -- so travel only positions this
         * point, it never sets the swipe length.
         */
        val inverseDistance =
            1.0f / distance

        val directionX =
            deltaX *
                inverseDistance

        val directionY =
            deltaY *
                inverseDistance

        val travel =
            MAX_TRAVEL -
                (
                    (
                        MAX_TRAVEL -
                            MIN_TRAVEL
                    ) * proximity
                ) // UPGRADE: Inverted logic fixed. Far = long swipe (fast approach), Close = short swipe (fine magnetic correction)

        // SPLENDOR_P10_TRAP_TRAVEL: pre-position (ball incoming but
        // outside the 0..6-frame trap window) halves the swipe toward
        // MIN_TRAVEL instead of a long chase swipe.
        val trapTravel = if (prePosition) MIN_TRAVEL + (travel - MIN_TRAVEL) * 0.5f else travel

        val targetX =
            (
                owner.x +
                    (
                        directionX *
                            trapTravel
                    )
            ).coerceIn(
                0.0f,
                SCREEN_MAX_X
            )

        val targetY =
            (
                owner.y +
                    (
                        directionY *
                            trapTravel
                    )
            ).coerceIn(
                0.0f,
                SCREEN_MAX_Y
            )

        /*
         * Fast correction when the ball is close;
         * slightly longer movement window at the outer edge.
         */
        val duration =
            when {
                proximity >= 0.75f -> 16L
                proximity >= 0.45f -> 20L
                else -> 24L
            }

        // SPLENDOR_P10_TRAP_LOG: one line on CLOSING window-enter
        // only (not per frame); authority here is the final boosted
        // value, so the log proves the boost that arbitration saw.
        // No68-B-D31: explicit Locale.US -- the bare "%.1f".format
        // used the device locale, so a comma-decimal locale wrote
        // unparseable log lines.
        if (enteredTrapWindow) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "TRAP window=CLOSING closingPxF=" + "%.1f".format(Locale.US, closingSpeed) +
                    " dist=" + "%.0f".format(Locale.US, distance) +
                    " defDist=" + "%.0f".format(Locale.US, nearestDefenderDist) +
                    " band=" + trapBand +
                    " ttcF=" + "%.1f".format(Locale.US, timeToContactF) +
                    " auth=" + "%.2f".format(Locale.US, authority),
                "MagneticFeet")
        }

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = duration,
            // No68-B-D19: the aim origin is this contribution's own
            // owner, not whatever frame.ownerId resolves to in
            // chooseRequest(). The contributor reads the unfiltered
            // SceneTracker snapshot while frame.entities is filtered by
            // FrameAssembler, so the owner can be missing from entities
            // and the old fallback (mover = null) dropped the request.
            aimOriginX = owner.x,
            aimOriginY = owner.y,
            aimEntityId = owner.id
        )
    }

    override fun reset() {
        // No68-B-D23: the engine reset never touched this file's own
        // prevTrapWindow, so the first CLOSING window after a reset
        // could go unlogged when the stale value was already CLOSING.
        prevTrapWindow = "none"
        MagneticFeetEngine.reset()
    }
}
