package com.assistant

import kotlin.math.*
import kotlin.math.hypot

object LowBlockContainmentEngine {

    /**
     * B4 / QD-3 (Q68-27): RESOLVED by queue item 9 (L2). The axis rework
     * has landed — this engine is X-axis geometry now, on the proven
     * attack model (goals at the X ends; F38-D4 live session: 8,018
     * pairs, goalCX at the X ends; DefensiveLineEngine No68-D-D15).
     *
     * X geometry, mirrored 1:1 from the old Y version:
     *   - "deep" = ball-X within DEEP_FRACTION (0.30) of the width from
     *     OUR goal edge. 0.30 is INHERITED, not invented: the old Y
     *     threshold (ballY > 0.70 × height) defined "deep" as the last
     *     30% of the axis toward the goal — the same definition,
     *     transposed onto X. No new threshold was chosen.
     *   - the line is VERTICAL: constant X near our goal (18% → 11% of
     *     the width from the goal edge as the ball deepens — mirror of
     *     the old 0.82 → 0.89 of height from the top), slots spread
     *     along Y. Rank follows current-Y order (mirror of No68-D-D19:
     *     slots follow rank along the line axis, now Y, so the line
     *     never crosses itself).
     *   - hazard mirrors the old ((ballY − 0.80h) / 0.20h): ball within
     *     the last 20% of the width from our goal.
     *   - ownGoalX comes from VisionCore (MarkingEngine convention:
     *     detected goal centre, else the attack-direction edge).
     *     ownGoalKnown=false means neither is available — the engine
     *     stays INACTIVE (hold positions) rather than shaping a line
     *     at a guessed goal side. A wrong-side line is worse than none.
     *   - active=true only while the ball is actually deep; the
     *     inactive branch still returns one hold-position movement per
     *     defender (No68-D-D20 shape contract), with active=false, so
     *     the frame gate can tell engaged from holding.
     */
    const val QD3_AXIS_RESOLVED = true

    /** "Deep" on the X axis: ball within this fraction of the width from our goal. Inherited from the old Y threshold (0.70 = last 30%); see QD-3 above. */
    const val DEEP_FRACTION = 0.30f

    /**
     * Deep low-block defensive cover and spatial squeeze.
     */
    fun applyLowBlockContainment(
        ballX: Float,
        ballY: Float,
        pitchWidth: Float,
        pitchHeight: Float,
        defenders: List<Pair<Float, Float>>,
        ownGoalX: Float = 0f,
        ownGoalKnown: Boolean = false
    ): DefensiveContainmentResult {
        if (defenders.isEmpty()) {
            return DefensiveContainmentResult(
                movements = emptyList(),
                active = false
            )
        }

        val safeWidth = pitchWidth.coerceAtLeast(1f)
        val safeHeight = pitchHeight.coerceAtLeast(1f)
        val updated = ArrayList<Pair<Float, Float>>(defenders.size)

        // No68-D-D20: when the ball is not deep the old code returned an
        // EMPTY list — a consumer expecting one movement per defender got
        // zero, so the shape contract (movements.size == defenders.size)
        // broke exactly when the line should be holding its shape.
        // Inactive now means "hold position": return the defenders where
        // they already are. Inactive also while the own goal is unknown
        // (QD-3): a line shaped at a guessed goal side is worse than
        // holding.
        if (!ownGoalKnown) {
            return DefensiveContainmentResult(movements = defenders.toList(), active = false)
        }
        // goalSide: +1 when our goal sits at the left edge (the line
        // stands to its right), -1 when it sits at the right edge.
        val goalSide = if (ownGoalX <= safeWidth * 0.5f) 1f else -1f
        val distToGoal = abs(ballX - ownGoalX)
        val activationThreshold = safeWidth * DEEP_FRACTION

        if (distToGoal >= activationThreshold) {
            return DefensiveContainmentResult(movements = defenders.toList(), active = false)
        }

        run {
            val ballDepthProgress = ((activationThreshold - distToGoal) / activationThreshold)
                .coerceIn(0f, 1f)

            // X mirror of the old baseTargetLineY = height * (0.82 +
            // progress * 0.07): the line sits 18% → 11% of the width
            // from our goal edge as the ball deepens.
            val baseTargetLineX = (ownGoalX + goalSide * safeWidth * (0.18f - (ballDepthProgress * 0.07f)))
                .coerceIn(0f, safeWidth)

            var closestDefenderIndex = -1
            var minDistanceToBall = Float.MAX_VALUE

            defenders.forEachIndexed { index, defender ->
                val distanceToBall = hypot(
                    (defender.first - ballX).toDouble(),
                    (defender.second - ballY).toDouble()
                ).toFloat()

                if (distanceToBall < minDistanceToBall) {
                    minDistanceToBall = distanceToBall
                    closestDefenderIndex = index
                }
            }

            // No68-D-D19, transposed: slots were assigned by INPUT index
            // order, so a defender listed first always took the leftmost
            // slot even when standing on the right — the line crossed
            // itself. Slots now follow each defender's RANK in current-Y
            // order (ties break by input index, so the order is
            // deterministic), while movements are still returned in input
            // order so a consumer matching by index gets the right
            // defender.
            val rankOf = IntArray(defenders.size) { index ->
                defenders.count { other ->
                    other.second < defenders[index].second
                } + defenders.subList(0, index).count { it.second == defenders[index].second }
            }

            val centralY = safeHeight * 0.5f

            defenders.forEachIndexed { index, defender ->
                val optimalY = (safeHeight / (defenders.size + 1)) * (rankOf[index] + 1)

                val hazardLevel = ((safeWidth * 0.20f - distToGoal) / (safeWidth * 0.20f))
                    .coerceIn(0f, 1f)

                val isClosestToBall = index == closestDefenderIndex

                val verticalBias = if (isClosestToBall) {
                    val challengerShiftRange = safeHeight * (0.18f + (hazardLevel * 0.10f))
                    ((ballY / safeHeight) - 0.5f) * challengerShiftRange
                } else {
                    val coverShiftRange = safeHeight * (0.14f + (hazardLevel * 0.06f))
                    val ballBias = ((ballY / safeHeight) - 0.5f) * coverShiftRange
                    val squeezeCenterBias = (centralY - optimalY) * (0.25f + (hazardLevel * 0.15f))
                    ballBias + squeezeCenterBias
                }

                val lerpFactor = 0.35f + (hazardLevel * 0.30f)

                val targetY = (
                    defender.second +
                        ((optimalY - defender.second) * lerpFactor) +
                        verticalBias
                    ).coerceIn(0f, safeHeight)

                updated.add(
                    Pair(
                        baseTargetLineX,
                        targetY
                    )
                )
            }
        }

        return DefensiveContainmentResult(
            movements = updated,
            active = true
        )
    }
}
