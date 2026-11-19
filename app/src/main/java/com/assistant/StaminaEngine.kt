package com.assistant

import com.assistant.runtime.RuntimeFrame

/**
 * G-B — Stamina / Fatigue Engine (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G11): CaptaincySkillEngine computes a
 * fatigueProxy but no contributor or engine reads it, and there is no
 * RuntimeFrame field for it — press and defence intensity never scaled
 * with fatigue.
 *
 * This engine does NOT reuse CaptaincySkillEngine's proxy: that proxy
 * only exists when a captain is designated (setCaptainDesignated) and
 * is a team-lift input, not a stamina input. StaminaEngine computes its
 * own fatigue from frame inputs that exist on every frame — defender
 * density (sustained press), opponent press frequency (L10 learning
 * readout), vision confidence (decision quality degrading as the team
 * tires) and the match minute (G-C match state; 0 when unknown, so an
 * unknown clock contributes nothing rather than a guessed value).
 *
 * Output: a press-intensity scale in [0.55, 1.0]. It never silences the
 * press entirely (floor 0.55) — a tired team still presses, just softer.
 * Consumers: BallPressContributor + DashPressureContributor multiply
 * their authority by the scale. No new contributor, no new dispatch.
 */
object StaminaEngine {

    const val MIN_PRESS_SCALE = 0.55f
    const val MAX_PRESS_SCALE = 1.0f

    private const val DENSITY_WEIGHT = 0.45f
    private const val PRESS_FREQ_WEIGHT = 0.25f
    private const val CONFIDENCE_WEIGHT = 0.15f
    private const val MINUTE_WEIGHT = 0.15f

    /** Fatigue in [0, 1] from frame inputs only. Pure. */
    fun fatigue(frame: RuntimeFrame): Float {
        val density = frame.defenderDensity.coerceIn(0f, 1f) * DENSITY_WEIGHT
        val pressFreq = frame.opponentPressFrequency.coerceIn(0f, 1f) * PRESS_FREQ_WEIGHT
        val confidence = (1f - frame.confidence.coerceIn(0f, 1f)) * CONFIDENCE_WEIGHT
        // Match minute 0 (unknown clock) contributes 0; minute 90
        // contributes the full weight. Never a guessed minute.
        val minute = (frame.matchMinute.coerceIn(0, 90) / 90f) * MINUTE_WEIGHT
        return (density + pressFreq + confidence + minute).coerceIn(0f, 1f)
    }

    /** Press-intensity scale in [0.55, 1.0]. 1.0 = fresh. Pure. */
    fun pressScale(frame: RuntimeFrame): Float =
        (MAX_PRESS_SCALE - 0.45f * fatigue(frame)).coerceIn(MIN_PRESS_SCALE, MAX_PRESS_SCALE)

    /** Press scale from an already-computed fatigue value. Pure. */
    fun pressScaleForFatigue(fatigue: Float): Float =
        (MAX_PRESS_SCALE - 0.45f * fatigue.coerceIn(0f, 1f))
            .coerceIn(MIN_PRESS_SCALE, MAX_PRESS_SCALE)
}
