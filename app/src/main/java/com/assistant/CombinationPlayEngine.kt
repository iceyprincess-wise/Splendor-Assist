package com.assistant

import com.assistant.runtime.RuntimeEntity
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
        // Offside gate — the defender-line check above does NOT establish
        // onside (offside is judged against the second-last opponent).
        // Reuses DecoyRun's rigorous judgment: OffsideRiskEstimationEngine
        // .analyze() with a REAL measured line. If the runner is offside,
        // the pass would be whistled — the plan is not viable.
        if (!runnerOnside(frame, runner, attackSign)) return none
        val tx = (runner.x + runner.velocityXPerSecond * PROJECTION_SECONDS)
            .coerceIn(0f, frame.captureWidth)
        val ty = (runner.y + runner.velocityYPerSecond * PROJECTION_SECONDS)
            .coerceIn(0f, frame.captureHeight)
        return CombinationPlan(true, runner.id, tx, ty)
    }

    /**
     * Offside verdict for one runner, reusing
     * OffsideRiskEstimationEngine.analyze() — the DecoyRun judgment.
     * The line is the deepest OUTFIELD opponent along the attack axis
     * (keeper excluded: he is not the last FIELD defender, exactly as
     * DecoyRun measures it). analyze() assumes attack +x, so a -x
     * attack mirrors the receiver and the line into a +x frame
     * (DecoyRun's analyzeWithRealLine). No outfield opponent visible
     * → the line is unmeasurable → the gate passes (never block a
     * play on missing data). Offside is judged at the pass — the
     * runner's CURRENT position; analyze()'s own 120ms projection
     * covers the momentum margin.
     */
    private fun runnerOnside(
        frame: RuntimeFrame,
        runner: RuntimeEntity,
        attackSign: Float
    ): Boolean {
        val opponents = frame.entities.filter { e -> !e.teammate && !e.isGoalkeeper }
        if (opponents.isEmpty()) return true
        val lineX = opponents.maxByOrNull { it.x * attackSign }?.x ?: return true
        val w = frame.captureWidth
        // analyze() reads only lane.receiver; the passer is never
        // touched, so the ball position is the honest placeholder.
        val passer = TrackedPlayer(
            id = -1,
            x = frame.ballX,
            y = frame.ballY,
            velocityX = 0f,
            velocityY = 0f,
            confidence = frame.confidence,
            isUserTeam = true,
            lastSeenFrame = frame.frameId
        )
        val receiver = TrackedPlayer(
            id = runner.id,
            x = runner.x,
            y = runner.y,
            velocityX = runner.velocityX,
            velocityY = runner.velocityY,
            confidence = runner.confidence,
            isUserTeam = true,
            lastSeenFrame = frame.frameId,
            velocityXPerSecond = runner.velocityXPerSecond,
            velocityYPerSecond = runner.velocityYPerSecond
        )
        val lane = PassingLane(
            passer = passer,
            receiver = receiver,
            distance = 0f,
            pressure = 0f,
            blocked = false,
            score = 0f
        )
        val result = if (attackSign > 0f) {
            OffsideRiskEstimationEngine.analyze(PassingLaneGraph(listOf(lane)), lineX)
        } else {
            val mirroredLane = lane.copy(
                receiver = receiver.copy(x = w - receiver.x, velocityX = -receiver.velocityX)
            )
            OffsideRiskEstimationEngine.analyze(
                PassingLaneGraph(listOf(mirroredLane)), w - lineX
            )
        }
        // Empty result cannot happen with a one-lane graph; default to
        // passing the gate rather than blocking on a missing verdict.
        return result.lanes.firstOrNull()?.safe ?: true
    }
}
