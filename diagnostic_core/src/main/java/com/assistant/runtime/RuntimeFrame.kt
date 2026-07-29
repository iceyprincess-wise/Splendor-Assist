package com.assistant.runtime

enum class ActionClass { PASS, SHOT, CROSS, DEFEND, EVADE, KEEPER, MOVE, NONE }
enum class EngineCapability { ATTACK, DEFENSE, MOVEMENT, PASSING, KEEPER, SUPPORT }

/* Immutable per-capture snapshot. The ONLY input a gameplay engine may read. */
data class RuntimeFrame(
    val frameId: Long,
    val timestampMs: Long,
    val hasBall: Boolean,
    val ballX: Float,
    val ballY: Float,
    val playerCount: Int,
    val opponentCount: Int,
    val laneCount: Int,
    val viableLaneCount: Int,
    val passTargetX: Float,
    val passTargetY: Float,
    val bestLaneConfidence: Float,
    val defenderDensity: Float,
    val confidence: Float,
    val enabled: Boolean,
    val panic: Boolean
) {
    val trusted: Boolean get() = enabled && confidence > 0f
}

data class EngineContribution(
    val engine: String,
    val actionClass: ActionClass,
    val targetX: Float,
    val targetY: Float,
    val authority: Float,
    val confidence: Float,
    val durationHintMs: Long
) {
    val weight: Float
        get() = authority.coerceIn(0f, 1f) * confidence.coerceIn(0f, 1f)
}

/* Uniform contract. Upgrade an engine's internals freely; this never changes. */
interface GameplayContributor {
    val engineName: String
    val capabilities: Set<EngineCapability>
    fun initialize() {}
    fun warmUp() {}
    fun update(frame: RuntimeFrame) {}
    fun contribute(frame: RuntimeFrame): EngineContribution?
    fun reset() {}
    fun shutdown() {}
}
