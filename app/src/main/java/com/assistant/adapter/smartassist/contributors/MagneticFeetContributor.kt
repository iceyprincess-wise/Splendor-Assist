package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.MagneticFeetEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

/*
 * MagneticFeet — ball‑control / movement / support contributor.
 *
 * The core engine now emits a *dynamic amplification* value that reflects how
 * “pressing” the current stabilisation is (high defender pressure + clear lane).
 * This contributor multiplies the authority by that amplification (capped by
 * the admin key) so that, during a fast counter‑attack, the assist becomes more
 * decisive.
 *
 * Additionally we add a tiny boost when the ball is moving fast – typical of a
 * quick transition.  The `RuntimeFrame` may expose a `ballSpeed` field; we
 * guard against its absence with reflection‑style safe‑calls so the code
 * continues to compile even if the field does not exist yet.
 *
 * All other behaviour (cap, panic factor, confidence blending) stays the same.
 */
object MagneticFeetContributor : GameplayContributor {
    override val engineName = "MagneticFeet"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // Bail out early if we don’t have a trusted frame or the ball isn’t ours.
        if (!frame.trusted || !frame.hasBall) return null

        // -----------------------------------------------------------------
        // Input conversion – unchanged
        // -----------------------------------------------------------------
        val pressure = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)

        // -----------------------------------------------------------------
        // Engine call – still the same signature
        // -----------------------------------------------------------------
        val result = MagneticFeetEngine.stabilize(pressure, strength)

        // -----------------------------------------------------------------
        // Authority – now also multiplied by the engine’s amplification.
        // -----------------------------------------------------------------
        val cap = 0.65f            // admin‑key default – unchanged
        val panicFactor = if (frame.panic) 0.5f else 1.0f

        // Engine amplification lives in the snapshot; we fall back to 0.4f
        // (the baseline value used inside the engine) if, for any reason,
        // the snapshot is unavailable.
        val amplification = MagneticFeetEngine.magneticFeetSnapshot()
            ?.amplification ?: 0.4f

        val rawAuthority = (result.touchRetention / 10f) * panicFactor * amplification
        val authority = rawAuthority.coerceIn(0f, cap)

        // -----------------------------------------------------------------
        // Confidence – weighted blend that favours the engine when it is
        // confident (possessionControl high) and the vision system when it
        // is more certain.
        // -----------------------------------------------------------------
        val possessionNorm = (result.possessionControl / 10f).coerceIn(0f, 1f)

        // Weight the engine 60 % when its own confidence > 0.7, otherwise 40 %.
        // This makes the assist stronger during clear tactical moments.
        val engineWeight = if (possessionNorm > 0.7f) 0.6f else 0.4f
        val visionWeight = 1f - engineWeight
        val confidence = ((frame.confidence * visionWeight) +
                (possessionNorm * engineWeight)).coerceIn(0f, 1f)

        // -----------------------------------------------------------------
        // Duration hint – unchanged mapping, but we also apply a tiny
        // speed‑based boost (max +10 ms) if the ball is moving fast.
        // -----------------------------------------------------------------
        val resistanceNorm = (result.interceptionResistance / 10f).coerceIn(0f, 1f)
        var durationHintMs = (15L + (resistanceNorm * 70f).toLong()).coerceIn(15L, 85L)

        // Optional fast‑ball boost: if the frame supplies a `ballSpeed`
        // (meters/second) we add up to +10 ms when speed > 8 m/s.
        // The reflective access avoids compilation errors on older SDKs.
        try {
            val speedProp = frame::class.java.getDeclaredField("ballSpeed")
            speedProp.isAccessible = true
            val speed = speedProp.getFloat(frame)
            if (speed > 8f) {
                val extra = ((min(speed, 15f) - 8f) / 7f * 10f).toLong()
                durationHintMs = (durationHintMs + extra).coerceAtMost(95L)
            }
        } catch (_: Throwable) {
            // No ballSpeed field – ignore, keep the original duration.
        }

        // -----------------------------------------------------------------
        // Build the contribution.
        // -----------------------------------------------------------------
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = authority,
            confidence = confidence,
            durationHintMs = durationHintMs
        )
    }
}
