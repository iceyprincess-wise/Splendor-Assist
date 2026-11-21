package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object JerseyColorSegmentation {

    enum class Team {
        USER,
        OPPONENT,
        GOALKEEPER,
        UNKNOWN
    }

    data class ClassificationResult(
        val team: Team,
        val confidence: Float
    )

    /**
     * Live team classifier (6a rewrite — the old rules assumed a RED user
     * shirt and classified ALL THREE of Ed's current strips UNKNOWN,
     * proven by running the old rules on the measured medians).
     *
     * Ground truth (measured 2026-11-19 from his screenshots, PLAN.md
     * kits audit): 1st Dortmund YELLOW shirt (median 179,150,42);
     * 2nd Dortmund BLACK shirt (30,27,37); 3rd Coke Limited Kit (ARG)
     * BLACK shirt (33,32,43) + light-blue/white striped shorts
     * (median 115,146,171). Means agree with the same verdicts.
     *
     * Rules (checked in this order — light-blue must precede the
     * saturated-blue opponent rule, which it would otherwise miss):
     * - YELLOW-dominant -> USER (1st kit shirt).
     * - LIGHT-BLUE-dominant -> USER (3rd kit shorts, supporting signal).
     * - GREEN-dominant -> GOALKEEPER (unchanged; keeper kit unknown,
     *   green is the keeper colour, not an outfield colour).
     * - Saturated BLUE -> OPPONENT (pre-existing opponent signal kept;
     *   opponent kits are otherwise UNKNOWN — do not invent them).
     * - RED-dominant -> UNKNOWN. The old red->USER rule is REMOVED, not
     *   restored: no current strip is red-dominant.
     * - BLACK / anything else -> UNKNOWN. Colour alone CANNOT separate
     *   his black kits (2nd all-black, 3rd black shirt) from dark
     *   opponent kits — any black->USER rule would swallow opponents.
     *   Black-kit players become visible ONLY via the blue-circle /
     *   direction indicator (PlayerDetector's ring rule), never via
     *   colour. Black-kit luminance (~28) is also dropped at the
     *   FrameScanner gate, by design — the gate keeps kind!=1 pixels.
     *
     * ForegroundPixel.kind() delegates to this function, so the two
     * live copies can never drift again. segment() was DELETED in
     * item 13 (zero callers; its red->USER rule contradicted the 6a
     * rules above) — do not revive as-is.
     */
    fun classify(
        red: Float,
        green: Float,
        blue: Float
    ): ClassificationResult {

        if (!red.isFinite() || !green.isFinite() || !blue.isFinite()) return ClassificationResult(Team.UNKNOWN, 0f)
        return when {

            // 1st kit: Dortmund yellow shirt.
            red > 140f && green > 100f && blue < 90f && red > green && green > blue ->
                ClassificationResult(Team.USER, ((red - maxOf(green, blue)) / 255f).coerceIn(0.5f, 1f))

            // 3rd kit: Coke light-blue shorts (supporting USER signal).
            blue > 140f && blue > red + 20f && blue > green + 15f && green > 100f ->
                ClassificationResult(Team.USER, ((blue - maxOf(red, green)) / 255f).coerceIn(0.5f, 1f))

            green > 160f && green > red + 60f && green > blue + 60f ->
                ClassificationResult(Team.GOALKEEPER, ((green - maxOf(red, blue)) / 255f).coerceIn(0.5f, 1f))

            blue > 140f && blue > red + 50f && blue > green + 50f ->
                ClassificationResult(Team.OPPONENT, ((blue - maxOf(red, green)) / 255f).coerceIn(0.5f, 1f))

            else ->
                ClassificationResult(
                    Team.UNKNOWN,
                    0f
                )
        }
    }
}
