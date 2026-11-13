package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*





import kotlin.math.hypot






















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
     * RuntimeDecisionLoop currently maps contributor targets to the joystick
     * origin at 250,550.
     */
    private val JOYSTICK_ORIGIN_X get() = com.assistant.vision.CameraProfile.captureWidthOrFallback() * (250f / 1649f)
    private val JOYSTICK_ORIGIN_Y get() = com.assistant.vision.CameraProfile.captureHeightOrFallback() * (550f / 719f)

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

    fun getMagneticRadius(): Float =
        MAGNETIC_RADIUS

    override fun initialize() {
        MagneticFeetEngine.reset()
    }

    override fun warmUp() {
        /*
         * No private memory graph exists anymore.
         * Registration lifecycle is sufficient.
         */
    }

    @Suppress("UNUSED_PARAMETER")
    override fun update(
        frame: RuntimeFrame
    ) {
        /*
         * RuntimeFrame is consumed atomically inside contribute().
         */
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
        val trapBandWeight = when {
            nearestDefenderDist <= 15f + trapMargin -> 1.0f
            nearestDefenderDist <= 45f + trapMargin -> 0.6f
            else -> 0.25f
        }
        val trapBand = when {
            nearestDefenderDist <= 15f + trapMargin -> "HOT"
            nearestDefenderDist <= 45f + trapMargin -> "PRESS"
            else -> "LOOSE"
        }
        val trapWindow = when {
            closingSpeed >= 5f -> "CLOSING"
            trapBallSpeed < 5f -> "SETTLED"
            else -> "LEAVING"
        }
        val timeToContactF = if (closingSpeed > 0f) (distance - 18f) / closingSpeed else Float.POSITIVE_INFINITY
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
         * Real proximity signal replaces the old defender-density proxy.
         */
        val pressure =
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
                pressure = pressure,
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
         * Shape the weighted authority before the requested 0.9..1.2
         * bounds so the result does not immediately hit the cap for every
         * moderately high touch value.
         */
        val shapedAuthority =
            weightedAuthority /
                (
                    1.0f +
                        (
                            weightedAuthority *
                                0.20f
                        )
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
        // SPLENDOR_P10_TRAP_BOOST: trap boost multiplies the shaped
        // authority BEFORE the P9 coerceIn(0.0f, cap), so the P9
        // unsaturation is preserved and the boost can never re-saturate
        // past the 1.2 cap / 1.0 weight clamp. trapBoost is 1.0 outside
        // the CLOSING 0..6-frame window.
        val authority =
            (shapedAuthority * trapBoost)
                .coerceIn(
                    0.0f,
                    cap
                )
                .coerceIn(
                    0.0f,
                    1.0f
                )

        /*
         * Convert the player->ball direction into joystick displacement.
         *
         * This is the critical coordinate-space repair:
         * absolute vision coordinates are NOT passed directly as joystick
         * gesture endpoints.
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
                JOYSTICK_ORIGIN_X +
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
                JOYSTICK_ORIGIN_Y +
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
        if (enteredTrapWindow) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "TRAP window=CLOSING closingPxF=" + "%.1f".format(closingSpeed) +
                    " dist=" + "%.0f".format(distance) +
                    " defDist=" + "%.0f".format(nearestDefenderDist) +
                    " band=" + trapBand +
                    " ttcF=" + "%.1f".format(timeToContactF) +
                    " auth=" + "%.2f".format(authority),
                "MagneticFeet")
        }

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = duration
        )
    }

    override fun reset() {
        MagneticFeetEngine.reset()
    }
}

/* ========
OverloadPlaystyleContributor
======== */
/*
 * Overload playstyle: primary mode is DEFENSIVE SWARM -- when the opponent
 * carries the ball, collapse numbers onto the carrier to deny build-up passes.
 * Hybrid inverse (ATTACKING_EXPLOIT) applies on regained possession.
 */
