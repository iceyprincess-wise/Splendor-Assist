package com.assistant.adapter.smartassist.contributors

import com.assistant.admin.AdminConfigStore
import com.assistant.adapter.smartassist.MagneticFeetEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

/*
 * MagneticFeet — ball-control / movement / support contributor.
 *
 * All three engine outputs are now live:
 *
 *   touchRetention        → authority
 *     Ball stickiness and support stability. Scales from the engine's
 *     [0,10] output to [0,1]. Halved during panic to yield to urgent
 *     actions. Capped by the admin key (default 0.65f).
 *
 *   interceptionResistance → durationHintMs
 *     Resistance to losing useful control under defensive pressure.
 *     Peaks when a clear escape lane co-occurs with real pressure (synergy).
 *     Maps engine [0,10] → gesture duration [15,85] ms.
 *
 *   possessionControl → confidence
 *     Contextual confidence that maintaining ball-control is tactically
 *     appropriate. Averaged with frame.confidence so both vision quality
 *     and tactical appropriateness must be high to produce a high weight.
 *     Depressed automatically by the engine when the player is trapped
 *     (high pressure, no viable lane).
 *
 * Authority cap: assist.contrib.magneticfeet.cap (executable default 0.65f).
 * Comment previously stated 0.35f in error — the executable always used 0.65f.
 * This comment now matches the executable.
 *
 * MOVE-class scaling applied by RuntimeDecisionLoop (assist.decision.move_scale,
 * default 0.35f) is preserved. MagneticFeet remains a SUPPORT contributor
 * that wins arbitration only when no stronger action-class contribution is
 * available. This is the correct role for a ball-control stabiliser.
 */
object MagneticFeetContributor : GameplayContributor {
    override val engineName = "MagneticFeet"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        val pressure = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)

        val result = MagneticFeetEngine.stabilize(pressure, strength)

        // ── authority ─────────────────────────────────────────────────────
        // Source: touchRetention (ball stickiness / support stability).
        // Engine output [0,10] → normalised [0,1].
        // During panic, halve authority to defer to urgent DEFEND/EVADE actions.
        // Capped by admin key; default 0.65f.
        val cap = try {
            AdminConfigStore.get("assist.contrib.magneticfeet.cap", 0.65f)
        } catch (_: Throwable) { 0.65f }

        val panicFactor = if (frame.panic) 0.5f else 1.0f
        val authority = ((result.touchRetention / 10f) * panicFactor)
            .coerceIn(0f, cap)

        // ── confidence ────────────────────────────────────────────────────
        // Source: possessionControl (contextual appropriateness of holding).
        // Averaged with frame.confidence so both vision quality AND tactical
        // confidence must be high. When trapped (high pressure, no lane),
        // possessionControl/10f approaches 0, depressing the combined score.
        val possessionNorm = (result.possessionControl / 10f).coerceIn(0f, 1f)
        val confidence = ((frame.confidence + possessionNorm) * 0.5f)
            .coerceIn(0f, 1f)

        // ── durationHintMs ────────────────────────────────────────────────
        // Source: interceptionResistance (ability to hold under pressure).
        // Engine output [0,10] maps to gesture duration [15,85] ms.
        // High resistance (clear lane under real pressure) → longer hold (85ms).
        // Baseline (no pressure, no lane) → minimal burst (15ms).
        val resistanceNorm = (result.interceptionResistance / 10f).coerceIn(0f, 1f)
        val durationHintMs = (15L + (resistanceNorm * 70f).toLong()).coerceIn(15L, 85L)

        return EngineContribution(
            engine        = engineName,
            actionClass   = ActionClass.MOVE,
            targetX       = frame.ballX.coerceAtLeast(0f),
            targetY       = frame.ballY.coerceAtLeast(0f),
            authority     = authority,
            confidence    = confidence,
            durationHintMs = durationHintMs
        )
    }
}
