package com.assistant.contributors

import com.assistant.runtime.*

/*
 * Pressure-escape contributor (Task C item (d) onboarding).
 *
 * Fires only in the specific trap the decision loop previously had no answer
 * for: we have the ball, defender density is high, and there is no viable
 * pass out. Uses the frame's real per-zone body counts to pick the escape
 * direction; if no zone is genuinely better, it stays silent instead of
 * dribbling into a wall. Fixed lateral offsets follow the established
 * in-repo pattern (KeeperBiasContributor uses the same technique).
 */
object PressEvadeContributor : GameplayContributor {
    override val engineName = "PressEvade"
    override val capabilities = setOf(EngineCapability.MOVEMENT)

    private const val PRESSURE_THRESHOLD = 0.45f
    private const val LANE_ESCAPE_CONFIDENCE = 0.35f
    private const val LATERAL_STEP_PX = 140f

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.defenderDensity < PRESSURE_THRESHOLD) return null
        // If a decent pass exists, PassLaneContributor handles the frame.
        if (frame.viableLaneCount > 0 &&
            frame.bestLaneConfidence > LANE_ESCAPE_CONFIDENCE
        ) return null

        // zones: 0=left 1=mid 2=right; balance +1 = fully ours
        var bestZone = 1
        var bestBalance = -2f
        for (z in 0..2) {
            val b = frame.zones.balanceIn(z)
            if (b > bestBalance) {
                bestBalance = b
                bestZone = z
            }
        }
        if (bestBalance <= 0f) return null // nowhere is actually better

        val offsetX = when (bestZone) {
            0 -> -LATERAL_STEP_PX
            2 -> LATERAL_STEP_PX
            else -> 0f
        }
        if (offsetX == 0f) return null // no lateral escape worth taking

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.EVADE,
            targetX = (frame.ballX + offsetX).coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = (0.3f + 0.4f * frame.defenderDensity.coerceIn(0f, 1f))
                .coerceIn(0f, 1f),
            confidence = (frame.confidence * (0.5f + 0.5f * bestBalance))
                .coerceIn(0f, 1f),
            durationHintMs = 55L
        )
    }
}
