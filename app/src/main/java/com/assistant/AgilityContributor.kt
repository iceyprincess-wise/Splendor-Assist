package com.assistant

import com.assistant.runtime.*
import com.assistant.vision.CameraProfile
import kotlin.math.*

// No58: AgilityContributor is registered (AppContributorRegistration, 43
// contributors) and reached through GameplayEngineRegistry.collect(), which
// is called once per assembled frame by RuntimeDecisionLoop.onFrame() on
// the OverlayService capture coroutine. It does NOT override fastGate, so
// the default (true) applies — the trusted-frame gate in collect() and
// observeAgility are the only gates before this code runs.
//
// V4 (SPLENDOR_AGILITY_UNITS_V4): the native engine now takes opponent
// velocity (oppVx/oppVy, real px/s from the observation — the
// FrameAssembler predictor fills every entity), a lead time and an
// aggression factor, and returns 10 floats. All ten outputs have a live
// consumer below: stabilityBoost -> authority, controlRetentionBoost ->
// confidence, turnAssist -> push distance, shieldAngle -> shield target,
// shieldDuration -> durationHintMs, shieldActive -> the gate, escapeAngle
// -> the burst target (via the latch), burstIntensity -> the latch,
// threatLevel -> authority modulation + the transition log, predictedDist
// -> the transition log. Nothing the native engine computes is discarded.
object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    // Caller-supplied native model configuration (SPLENDOR_AGILITY_UNITS_V4).
    // The native function fabricates no unit scales; every scale below is
    // supplied by this call site, so this block and ActiveGestureController
    // are the only places these numbers live. Both callers use the same
    // scales except maxVelocity: this contributor passes the capture
    // diagonal (observed.referenceSpeed) as the velocity ceiling, because
    // its velocity is raw px/s; ActiveGestureController passes 15.0f
    // because its velocity is already normalised into a 0..15 space.
    private const val NEAR_UNITS = 100.0f
    private const val PROX_UNITS = 220.0f
    private const val SOFT_UNITS = 500.0f
    private const val FAR_UNITS = 1200.0f
    private const val SHIELD_BASE = 4.0f
    private const val SHIELD_PROX = 6.0f
    private const val SHIELD_SPEED = 3.0f
    private const val SHIELD_CONF = 2.0f
    private const val DURATION_BASE = 45.0f
    private const val CONF_FLOOR = 0.3f
    private const val DIST_GUARD = 0.5f

    // V4 predictive-kinematics defaults. These MUST match the C fallbacks
    // in native_agility_physics.c (sanitize_finite fallbacks 0.18f / 1.35f)
    // and the ActiveGestureController call site, so a caller that passes
    // garbage and a caller that passes these agree on the same behaviour.
    // LEAD_TIME_SEC 0.18s is inside the V4 prose range 0.15–0.25s: long
    // enough for the predicted defender position to differ from the
    // current one at sprint speed (~40 px/s -> ~7 px), short enough that
    // the prediction does not outrun the next frame. AGGRESSION_FACTOR
    // 1.35 is the V4 default: the fuzz envelope widens (upper 2.6*1.35,
    // lower 1.25*1.35) and the boosts scale, but every output stays inside
    // its documented range because the native clamps are load-bearing
    // (verified: at 1.35 with full inputs shieldBoost raw 25.31 -> clamped
    // 15, controlRetentionBoost 1.35 -> 1, turnAssist 1.485 -> 1).
    private const val LEAD_TIME_SEC = 0.18f
    private const val AGGRESSION_FACTOR = 1.35f

    // ZERO-ALLOCATION JNI BUFFER: 10 floats produced by native C ABI (V4)
    // [0]=stabilityBoost, [1]=controlRetentionBoost, [2]=turnAssist, [3]=shieldAngle,
    // [4]=shieldDuration, [5]=shieldActive, [6]=escapeAngle, [7]=burstIntensity,
    // [8]=threatLevel, [9]=predictedDist
    // Threading: this buffer is shared because collect() runs inside
    // RuntimeDecisionLoop.onFrame(), which is @Synchronized, and collect()
    // has exactly one caller (onFrame) — contributions can never overlap.
    // The other native-agility caller (ActiveGestureController) uses its
    // own local buffer. Do not call contribute() from a second thread
    // without replacing this buffer with a local one.
    private val nativeAgilityOut = FloatArray(10)

    // V4 shield -> burst transition (gesture chaining). The contributor
    // returns one MOVE contribution per frame, so the transition is
    // temporal: while the shield is active the target follows shieldAngle;
    // when burstIntensity crosses the latch the target switches to
    // escapeAngle for the exit. The hysteresis latch (on > 0.75, off < 0.5)
    // keeps a single noisy frame from flipping the direction mid-gesture.
    // The latch is a plain var because contribute() runs only inside the
    // @Synchronized onFrame path (see the buffer note above).
    private val burstLatch = AgilityBurstLatch()

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val observed = observeAgility(frame) ?: return null
        val ballX = observed.x
        val ballY = observed.y

        val success = com.assistant.NativeBridge.nativeComputeAgilityPhysics(
            observed.speed, observed.distance, observed.directionDegrees, observed.confidence, observed.turnIntensity,
            ballX, ballY, observed.opponentX, observed.opponentY,
            observed.oppVelocityXPerSecond, observed.oppVelocityYPerSecond,
            LEAD_TIME_SEC, AGGRESSION_FACTOR,
            nativeAgilityOut,
            NEAR_UNITS, PROX_UNITS, SOFT_UNITS, FAR_UNITS,
            observed.referenceSpeed, SHIELD_BASE, SHIELD_PROX, SHIELD_SPEED, SHIELD_CONF,
            DURATION_BASE, CONF_FLOOR, DIST_GUARD
        )

        // The native shieldActive is exactly 0.0 or 1.0, so the 0.1f
        // threshold is a boolean gate, not a tuned value. ActiveGestureController
        // uses the same > 0.1f threshold on the same signal, so both paths
        // agree on when the shield is active.
        if (!success || nativeAgilityOut[5] < 0.1f) {
            // Shield dropped: reset the latch so a later re-engagement
            // starts from the shield side, not from a stale burst.
            burstLatch.reset()
            return null
        }

        val stabilityBoost = nativeAgilityOut[0]
        val controlRetentionBoost = nativeAgilityOut[1]
        val turnAssist = nativeAgilityOut[2].coerceIn(0f, 1f)
        val shieldDuration = nativeAgilityOut[4]
        val threatLevel = nativeAgilityOut[8].coerceIn(0f, 1f)
        val predictedDist = nativeAgilityOut[9]

        // V4 burst transition: the latch reads this frame's burstIntensity
        // and holds its state across frames. Escape wins only while latched.
        val bursting = burstLatch.update(nativeAgilityOut[7])
        val angleDegrees = if (bursting) nativeAgilityOut[6] else nativeAgilityOut[3]
        val angleRad = Math.toRadians(angleDegrees.toDouble())
        val pushDist = 75.0f + turnAssist * 45.0f
        val calculatedTargetX = (ballX + kotlin.math.cos(angleRad) * pushDist).toFloat().coerceIn(0f, CameraProfile.captureWidthOrFallback())
        val calculatedTargetY = (ballY + kotlin.math.sin(angleRad) * pushDist).toFloat().coerceIn(0f, CameraProfile.captureHeightOrFallback())
        // No58 derivations, verified against the native ranges:
        // - authority: native stabilityBoost is clamped 1.5..15, so / 15 is
        //   0.1..1.0 before the 0.95 cap; the cap is what keeps a maxed-out
        //   boost from claiming full authority. threatLevel (0..1) modulates
        //   it: a maxed-out boost under no threat is worth 0.75 of itself,
        //   under full threat the full value — the threat is what the
        //   shield is reacting to, so it belongs in the weight, not only in
        //   the log. The 0.1 floor is what keeps a near-zero native value
        //   from zeroing the contribution (weight = authority * confidence,
        //   and the registry drops weight <= 0).
        // - confidence: native controlRetentionBoost is 0..1, so the 0.1
        //   floor is what keeps a near-zero native value from zeroing the
        //   contribution weight.
        // - durationHintMs: native shieldDuration is clamped 45..160 (V4),
        //   so this clamp takes the native range exactly. chooseRequest()
        //   still coerces every duration to 15..85 downstream, so the
        //   truncation above 85 is a downstream property of the terminal,
        //   not of this contribution — this clamp no longer hides native
        //   values the way the V3 16..100 clamp truncated 101..124.
        val authority = ((stabilityBoost / 15.0f) * (0.75f + 0.25f * threatLevel)).coerceIn(0.1f, 0.95f)
        val confidence = controlRetentionBoost.coerceIn(0.1f, 1f)
        val durationHintMs = shieldDuration.toLong().coerceIn(45L, 160L)

        // V4 transition log (MagneticFeetContributor pattern: one line on
        // the transition, not per frame): shield activation and the burst
        // trigger are the two events the theater/dashboard can read. The
        // per-frame threatLevel/predictedDist live here, not in a discarded
        // local — this log line is their consumer on this path.
        if (bursting && !burstLatch.wasLatchedBeforeUpdate) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "Agility BURST exit threat=$threatLevel predictedDist=$predictedDist escapeAngle=$angleDegrees",
                "Agility"
            )
        }

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = calculatedTargetX,
            targetY = calculatedTargetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = durationHintMs,
            aimOriginX = observed.x, aimOriginY = observed.y, aimEntityId = observed.carrierId
        )
    }
}
