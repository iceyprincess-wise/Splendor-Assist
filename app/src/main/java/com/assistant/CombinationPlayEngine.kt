package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-L — Combination Play (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G7): no combination play — no one-twos,
 * no third-man runs. Offside trap / third-man / one-two: all 0 files.
 *
 * The spec flags this as the hardest proposal: the current arbitration
 * takes SINGLE offers, so a multi-step offer (pass + run + return)
 * needs an arbitration extension. That extension is NOT built here —
 * it is stated, not assumed: a sequence offer cannot be arbitrated by
 * a single-offer arbiter, and inventing a sequence channel in this
 * batch would be speculative creation beyond the spec.
 *
 * What IS built: the FIRST STEP of a one-two as an ordinary single
 * offer. CombinationPlayContributor offers the pass to the runner's
 * projected position; when the runner receives, the next frame's plan
 * re-computes (the return pass is then the ordinary best pass, or the
 * next one-two step). The plan itself is this engine: passer = the
 * owner, runner = the teammate moving upfield (velocity toward the
 * attack end) ahead of the ball, with a defender line between ball and
 * runner (the line the one-two beats). If no runner is making the run,
 * there is no combination to play.
 */
data class CombinationPlan(
    val viable: Boolean,
    val runnerId: Int?,
    val targetX: Float,
    val targetY: Float
)

object CombinationPlayEngine {

    /** Runner must be this far ahead of the ball to be a run, not a layoff. */
    const val RUN_AHEAD_FRACTION_OF_WIDTH = 0.10f
    /** Projection horizon for the runner's position (seconds). */
    const val PROJECTION_SECONDS = 0.3f

    fun plan(frame: RuntimeFrame): CombinationPlan {
        val none = CombinationPlan(false, null, 0f, 0f)
        if (!frame.trusted || !frame.hasBall) return none
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return none
        val attackSign = if (frame.attackDirection < 0) -1f else 1f
        val w = frame.captureWidth
        // Runner: teammate ahead of the ball, moving toward the attack end.
        val runner = frame.entities
            .filter { e ->
                e.teammate &&
                    (e.x - frame.ballX) * attackSign > RUN_AHEAD_FRACTION_OF_WIDTH * w &&
                    e.velocityXPerSecond * attackSign > 0f
            }
            .maxByOrNull { e -> (e.x - frame.ballX) * attackSign } ?: return none
        // A defender line between ball and runner is what the one-two beats:
        // at least one opponent between them along the attack axis.
        val lineBeaten = frame.entities.any { e ->
            !e.teammate &&
                (e.x - frame.ballX) * attackSign > 0f &&
                (runner.x - e.x) * attackSign > 0f
        }
        if (!lineBeaten) return none
        val tx = (runner.x + runner.velocityXPerSecond * PROJECTION_SECONDS)
            .coerceIn(0f, frame.captureWidth)
        val ty = (runner.y + runner.velocityYPerSecond * PROJECTION_SECONDS)
            .coerceIn(0f, frame.captureHeight)
        return CombinationPlan(true, runner.id, tx, ty)
    }
}
