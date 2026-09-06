package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.MagneticFeetEngine
import com.assistant.adapter.smartassist.Phase3WorldStateStore
import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * Vision-backed Magnetic Feet contributor.
 *
 * The previous version depended on unimplemented native-memory pointers.
 * This version consumes the already-live runtime sources:
 *
 *   RuntimeFrame
 *     -> possession verdict
 *     -> tracked owner
 *     -> ball/player distance
 *     -> bounded joystick vector
 *
 * The contributor does not own input dispatch. It emits a MOVE contribution
 * which follows the repository's normal RuntimeDecisionLoop -> execution bus
 * -> accessibility gesture path.
 */
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
    private const val JOYSTICK_ORIGIN_X = 250.0f
    private const val JOYSTICK_ORIGIN_Y = 550.0f

    private const val MIN_TRAVEL = 45.0f
    private const val MAX_TRAVEL = 180.0f

    private const val SCREEN_MAX_X = 1649.0f
    private const val SCREEN_MAX_Y = 719.0f

    /*
     * Same live floor used by FrameAssembler for possession confidence.
     */
    private const val POSSESSION_MIN_CONFIDENCE = 0.20f

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
        val authority =
            shapedAuthority
                .coerceIn(
                    0.9f,
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
            MIN_TRAVEL +
                (
                    (
                        MAX_TRAVEL -
                            MIN_TRAVEL
                    ) * proximity
                )

        val targetX =
            (
                JOYSTICK_ORIGIN_X +
                    (
                        directionX *
                            travel
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
                            travel
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
