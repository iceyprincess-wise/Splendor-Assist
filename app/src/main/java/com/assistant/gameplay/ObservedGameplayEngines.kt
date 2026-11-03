package com.assistant.gameplay

import com.assistant.runtime.*
import kotlin.math.abs
import kotlin.math.hypot

/** All distances and velocities are capture pixels and pixels per observed frame.
 * Defender identity is the vision estimate, never a claim of game cursor selection.
 * Confidence-dependent margins handle uncertain boundaries; they do not prove low graphics accuracy.
 */
abstract class ObservedGameplayEngine : GameplayContributor {
    override fun fastGate(frame: RuntimeFrame) = frame.trusted && !frame.panic &&
        frame.captureWidth > 0f && frame.captureHeight > 0f && frame.entities.isNotEmpty()
    protected fun margin(frame: RuntimeFrame) = 5f * (1f - frame.confidence.coerceIn(0f, 1f))
    protected fun entity(frame: RuntimeFrame, id: Int?) = frame.entities.firstOrNull { it.id == id }
    protected fun candidate(frame: RuntimeFrame, action: ActionClass, origin: RuntimeEntity,
        x: Float, y: Float, authority: Float, duration: Long, slot: Int = 0): EngineContribution? {
        if (!x.isFinite() || !y.isFinite() || x !in 0f..frame.captureWidth || y !in 0f..frame.captureHeight) return null
        return EngineContribution(engineName, action, x, y, authority,
            minOf(frame.confidence, origin.confidence), duration, slot, origin.x, origin.y)
    }
}

class DreadnoughtBumperEngine : ObservedGameplayEngine() {
    override val engineName = "DREADNOUGHT_BUMPER"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!fastGate(frame) || frame.hasBall) return null
        val opponent = entity(frame, frame.ownerId)?.takeUnless { it.teammate } ?: return null
        val defender = entity(frame, frame.defenderId)?.takeIf { it.teammate } ?: return null
        val distance = hypot(opponent.x - defender.x, opponent.y - defender.y)
        if (distance > 45f + margin(frame)) return null
        val close = distance <= 15f + margin(frame)
        return candidate(frame, ActionClass.DEFEND, defender,
            opponent.x + opponent.velocityX * 3f, opponent.y + opponent.velocityY * 3f,
            if (close) 0.95f else 0.85f, if (close) 60L else 120L, if (close) 2 else 4)
    }
}

class FormationsAnchorEngine : ObservedGameplayEngine() {
    override val engineName = "FORMATIONS_ANCHOR"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.MOVEMENT)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!fastGate(frame) || frame.hasBall || frame.attackDirection == 0) return null
        val defender = entity(frame, frame.defenderId)?.takeIf { it.teammate } ?: return null
        val teammates = frame.entities.filter { it.teammate && it.id != defender.id }
        if (teammates.size < 3) return null
        // Observed back line along the landscape attack axis; no fabricated home zone/role.
        val back = teammates.sortedBy { it.x * frame.attackDirection }.take(3)
        val baseline = back.map { it.x }.average().toFloat()
        if ((defender.x - baseline) * frame.attackDirection <= 60f + margin(frame)) return null
        return candidate(frame, ActionClass.DEFEND, defender, baseline, defender.y, 0.96f, 180L)
    }
}

class AbsoluteMagneticFeetEngine : ObservedGameplayEngine() {
    override val engineName = "MAGNETIC_FEET"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.MOVEMENT)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!fastGate(frame) || frame.hasBall) return null
        val vx = frame.ballVelocityX; val vy = frame.ballVelocityY
        val speedSquared = vx * vx + vy * vy
        if (!speedSquared.isFinite() || speedSquared < 25f) return null
        // Use the estimated defender only. There is no observed selected-player signal to
        // justify steering an unselected teammate or asserting that a switch succeeded.
        val defender = entity(frame, frame.defenderId)?.takeIf { it.teammate } ?: return null
        val t = ((defender.x - frame.ballX) * vx + (defender.y - frame.ballY) * vy) / speedSquared
        if (t !in 0f..6f) return null // forward ray, bounded prediction horizon
        val x = frame.ballX + vx * t; val y = frame.ballY + vy * t
        if (hypot(defender.x - x, defender.y - y) > 25f + margin(frame)) return null
        return candidate(frame, ActionClass.DEFEND, defender, x, y, 0.94f, 90L)
    }
}

class LaserPassDirectorEngine : ObservedGameplayEngine() {
    override val engineName = "LASER_PASS_DIRECTOR"
    override val capabilities = setOf(EngineCapability.ATTACK, EngineCapability.PASSING)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!fastGate(frame) || !frame.hasBall || frame.attackDirection == 0) return null
        val passer = entity(frame, frame.ownerId)?.takeIf { it.teammate } ?: return null
        val opponents = frame.entities.filter { !it.teammate }
        if (opponents.isEmpty()) return null // absence of detections is not proof of a clear lane
        val targets = frame.entities.filter { it.teammate && it.id != passer.id }
            .sortedByDescending { (it.x - passer.x) * frame.attackDirection }
        for (target in targets) {
            val x = target.x + target.velocityX * 5f
            val y = target.y + target.velocityY * 5f
            if ((x - passer.x) * frame.attackDirection <= 0f) continue
            val dx = x - passer.x; val dy = y - passer.y; val length = dx * dx + dy * dy
            if (length <= 0f) continue
            val obstructed = opponents.any { opponent ->
                val t = (((opponent.x - passer.x) * dx + (opponent.y - passer.y) * dy) / length).coerceIn(0f, 1f)
                hypot(opponent.x - passer.x - t * dx, opponent.y - passer.y - t * dy) < 18f + margin(frame)
            }
            if (!obstructed) return candidate(frame, ActionClass.PASS, passer, x, y, 0.91f, 100L, 1)
        }
        return null
    }
}

class ApexFinisherEngine : ObservedGameplayEngine() {
    override val engineName = "APEX_FINISHER"
    override val capabilities = setOf(EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!fastGate(frame) || !frame.hasBall || !frame.goalDetected || !frame.goalkeeperVisible ||
            frame.goalConfidence < 0.45f || frame.attackDirection == 0) return null
        val shooter = entity(frame, frame.ownerId)?.takeIf { it.teammate } ?: return null
        val width = frame.goalWidth
        if (width <= 10f || frame.goalBottomY <= frame.goalTopY ||
            !frame.goalkeeperX.isFinite() || !frame.goalkeeperY.isFinite()) return null
        if ((frame.goalCenterX - shooter.x) * frame.attackDirection <= 0f) return null
        // Proximity heuristic, not a penalty-area detector or keeper-team classifier.
        if (hypot(frame.ballX - frame.goalCenterX, frame.ballY - frame.goalCenterY) > width * 2f ||
            hypot(frame.goalkeeperX - frame.goalCenterX, frame.goalkeeperY - frame.goalCenterY) > width) return null
        val left = frame.goalLeftX + minOf(5f, width * 0.1f)
        val right = frame.goalRightX - minOf(5f, width * 0.1f)
        val y = frame.goalCenterY
        val x = if (hypot(frame.goalkeeperX - left, frame.goalkeeperY - y) >
            hypot(frame.goalkeeperX - right, frame.goalkeeperY - y)) left else right
        return candidate(frame, ActionClass.SHOT, shooter, x, y, 0.98f, 80L, 2)
    }
}
