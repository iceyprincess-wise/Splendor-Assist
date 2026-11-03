package com.assistant.execution

import com.assistant.runtime.ActionClass
import kotlin.math.hypot

data class AimMotion(val originX: Float, val originY: Float, val targetX: Float, val targetY: Float,
    val relativeVx: Float, val relativeVy: Float, val predictedAtNanos: Long,
    val radius: Float, val width: Float, val height: Float) {
    fun endpoint(startX: Float, startY: Float, nowNanos: Long): Pair<Float, Float>? {
        if (listOf(originX, originY, targetX, targetY, relativeVx, relativeVy, radius, width, height).any { !it.isFinite() } ||
            radius <= 0f || width <= 1f || height <= 1f) return null
        val dt = ((nowNanos - predictedAtNanos).coerceIn(0L, 100_000_000L)) / 1e9f
        val dx = targetX - originX + relativeVx * dt
        val dy = targetY - originY + relativeVy * dt
        val length = hypot(dx, dy)
        if (!length.isFinite() || length <= 0f) return null
        return (startX + dx / length * radius).coerceIn(0f, width - 1f) to
            (startY + dy / length * radius).coerceIn(0f, height - 1f)
    }
}

/** Separate from tactical emergency panic. No settings override bypasses a blocked UI. */
object UiExecutionGate {
    @Volatile private var blockedUntilNanos = 0L
    @Volatile var reason: String = "none"; private set
    fun blocked(now: Long = System.nanoTime()) = now < blockedUntilNanos
    @Synchronized fun block(reason: String, now: Long = System.nanoTime(), durationMs: Long = 1500L) {
        this.reason = reason
        blockedUntilNanos = maxOf(blockedUntilNanos, now + durationMs.coerceIn(1, 5000) * 1_000_000)
        CentralExecutionBus.clearPending()
    }
    @Synchronized fun reset() { blockedUntilNanos = 0; reason = "none" }
}

/** Pixel macro-state correlation, never proof of internal game success. */
class OutcomePolicy {
    private data class Waiting(val phase: ActionClass, val ownerId: Int?, val hasBall: Boolean, val at: Long)
    private val pending = linkedMapOf<String, Waiting>()
    private val reliability = mutableMapOf<ActionClass, Float>()
    @Synchronized fun dispatched(id: String, phase: ActionClass, ownerId: Int?, hasBall: Boolean, now: Long) {
        if (phase == ActionClass.MOVE || phase == ActionClass.EVADE || phase == ActionClass.NONE) return
        if (pending.size >= 64) pending.remove(pending.keys.first())
        pending[id] = Waiting(phase, ownerId, hasBall, now)
    }
    @Synchronized fun cooling(phase: ActionClass, now: Long) = pending.values.any { it.phase == phase && now - it.at in 0L..800_000_000L }
    @Synchronized fun confidenceFactor(phase: ActionClass) = reliability[phase] ?: 1f
    @Synchronized fun cancel(id: String) { pending.remove(id) }
    @Synchronized fun observe(ownerId: Int?, hasBall: Boolean, fresh: Boolean, now: Long, observedAtNanos: Long = now): List<Pair<String, Boolean>> {
        val outcomes = mutableListOf<Pair<String, Boolean>>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (id, item) = iterator.next()
            val age = now - item.at
            val macro = fresh && observedAtNanos > item.at && age in 30_000_000L..800_000_000L && when(item.phase) {
                ActionClass.PASS, ActionClass.CROSS -> item.hasBall && hasBall && item.ownerId != null && ownerId != null && ownerId != item.ownerId
                ActionClass.DEFEND, ActionClass.KEEPER -> !item.hasBall && hasBall
                ActionClass.SHOT -> item.hasBall && !hasBall
                else -> false // Macro correlation never authenticates a goal or internal action success.
            }
            if (!macro && age <= 800_000_000L) continue
            val factor = confidenceFactor(item.phase)
            reliability[item.phase] = (factor + if (macro) 0.02f else -0.05f).coerceIn(0.6f, 1f)
            outcomes.add(id to macro); iterator.remove()
        }
        return outcomes
    }
    @Synchronized fun abandonPending() { pending.clear() }
    @Synchronized fun reset() { pending.clear(); reliability.clear() }
}
