package com.assistant

import com.assistant.vision.CameraProfile

import kotlin.math.*

/*
 * No68-B-D1: the instance half of this class is gone. MagneticDashAnchor
 * used to be a dribble injector: constructed with a
 * LatencyDefeatingInputEngine, processHighSpeedDribble() fired raw swipes
 * straight into the bus on every pulse. A repo-wide grep at base
 * b6463057 proves zero call sites and zero constructions -- the injector
 * never ran -- and wiring it would have bypassed ConAI arbitration, the
 * cooling/slot gates and RuntimeDecisionLoop entirely: a second,
 * unarbitrated dispatch route duplicating the live static path below.
 * Per the standing queue-clearing rule (wire if wireable, else REMOVE
 * with proof) and Ed's 2026-11-18 sweep authorization, the dead
 * instance is removed; what remains is the live static anchor
 * computation consumed by DashAnchorContributor.
 *
 * Defects removed with it (recorded, not silently dropped):
 * D2 bus-rejection ignored (pulse clock advanced on a lost swipe),
 * D3 no finite-input guard (NaN flowed to the bus), D4 first-call
 * drag-velocity spike measured against the (0,0) baseline, D5
 * frame-rate-dependent px/call duration branches, D12 atan2/cos/sin
 * round-trip, D13 the "pacing scramble" that was a deterministic
 * function of nanoTime's low nibble, D16 non-atomic instance state.
 */
object MagneticDashAnchor {

    private const val PURE_DRIFT_BETA = 0.85f
    private const val PURE_TURN_THRESHOLD = 6.0f

    @Volatile
    private var prevDirectionalX = 0f

    @Volatile
    private var prevDirectionalY = 0f

    // No68-B-D6: false until the first real directional sample lands,
    // so the first call seeds the baseline instead of measuring a drag
    // velocity against (0,0) (which reported e.g. 894 px/call for a
    // (800,400) target and forced a 10 ms duration in
    // DashAnchorContributor).
    @Volatile
    private var prevValid = false

    /*
     * Stateless anchor computation: drift-filtered directional target plus a
     * bounded strength. Synchronized with MagneticDashAnchor adhesion logic.
     */
    @JvmStatic
    @Synchronized
    fun computeAnchorTarget(
        dashX: Float,
        dashY: Float,
        directionalX: Float,
        directionalY: Float
    ): DashAnchorResult {
        // No68-B-D9: non-finite input can never produce a usable anchor.
        // Strength 0 makes DashAnchorContributor drop the contribution
        // (its `strength <= 0f` guard); the anchor is the dash point.
        if (
            !dashX.isFinite() ||
            !dashY.isFinite() ||
            !directionalX.isFinite() ||
            !directionalY.isFinite()
        ) {
            return DashAnchorResult(
                anchorX = if (dashX.isFinite()) dashX else 0f,
                anchorY = if (dashY.isFinite()) dashY else 0f,
                dragVelocity = 0f,
                turning = false,
                strength = 0f
            )
        }

        val dx = directionalX - dashX
        val dy = directionalY - dashY
        val dist = sqrt(dx * dx + dy * dy)

        // Track directional drag velocity across consecutive calls.
        // First call seeds the baseline; its velocity is 0 (D6).
        // Synchronized: the two previous-coordinate writes and the read
        // are one atomic step, so a concurrent caller can never observe
        // a new X with a stale Y (D16, static half).
        val dragVelocity = if (!prevValid) {
            prevDirectionalX = directionalX
            prevDirectionalY = directionalY
            prevValid = true
            0f
        } else {
            val velX = directionalX - prevDirectionalX
            val velY = directionalY - prevDirectionalY
            prevDirectionalX = directionalX
            prevDirectionalY = directionalY
            sqrt(velX * velX + velY * velY)
        }

        // Apply magnetic adhesion projection to contributor target
        val targetDist = if (dist > 0.001f) dist * ADHESION_COEFFICIENT else 0f
        val normX = if (dist > 0.001f) dx / dist else 1f
        val normY = if (dist > 0.001f) dy / dist else 0f

        val rawTargetX = dashX + normX * targetDist
        val rawTargetY = dashY + normY * targetDist

        // No68-B-D10: this is not a temporal filter -- there is no
        // previous output to blend with. It is a fixed 0.85 shrink of
        // the adhesion-extended target back toward the dash point, so
        // the anchor sits at 1.25 * 0.85 = 1.0625x the dash->directional
        // distance along the ray. (The removed instance path blended
        // against the previous directional with beta 0.90; the two paths
        // never agreed, and the comment claimed filtering.)
        val filteredX = (PURE_DRIFT_BETA * rawTargetX) + ((1f - PURE_DRIFT_BETA) * dashX)
        val filteredY = (PURE_DRIFT_BETA * rawTargetY) + ((1f - PURE_DRIFT_BETA) * dashY)

        // No68-B-D8: turning is a velocity property only. The old
        // `|| dist > 120f` labelled every far target as turning, so a
        // straight far run took the 25 ms duration in
        // DashAnchorContributor for distance alone.
        val turning = dragVelocity > PURE_TURN_THRESHOLD
        // No68-B-D7: the 0.35 floor applies at zero distance too. The
        // old code returned 0.5 when dist == 0, so a stationary ball
        // bid HIGHER than a ball 10 px away.
        val strength = if (dist > 0f) (dist / 150f).coerceIn(0.35f, 1.0f) else 0.35f

        // No68-B-D11: the old code clamped only below zero; an anchor
        // beyond the capture edge passed straight through to the
        // contribution (the registry checks finite only). Clamp both
        // ends to the live capture geometry.
        return DashAnchorResult(
            anchorX = filteredX.coerceIn(0f, CameraProfile.captureWidthOrFallback()),
            anchorY = filteredY.coerceIn(0f, CameraProfile.captureHeightOrFallback()),
            dragVelocity = dragVelocity,
            turning = turning,
            strength = strength
        )
    }
}
