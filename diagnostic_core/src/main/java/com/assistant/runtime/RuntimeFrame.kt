package com.assistant.runtime

enum class ActionClass { PASS, SHOT, CROSS, DEFEND, EVADE, KEEPER, MOVE, NONE }
enum class EngineCapability { ATTACK, DEFENSE, MOVEMENT, PASSING, KEEPER, SUPPORT }

/* Immutable per-capture snapshot. The ONLY input a gameplay engine may read. */
/* Per-zone body count. Owned by FrameAssembler; read-only to engines. */
data class ZoneDistribution(
    val leftOurs: Int = 0,  val leftTheirs: Int = 0,
    val midOurs: Int = 0,   val midTheirs: Int = 0,
    val rightOurs: Int = 0, val rightTheirs: Int = 0
) {
    fun oursIn(z: Int) = when (z) { 0 -> leftOurs; 1 -> midOurs; else -> rightOurs }
    fun theirsIn(z: Int) = when (z) { 0 -> leftTheirs; 1 -> midTheirs; else -> rightTheirs }
    fun balanceIn(z: Int): Float {
        val o = oursIn(z); val x = theirsIn(z); val tot = o + x
        return if (tot > 0) ((o - x).toFloat() / tot).coerceIn(-1f, 1f) else 0f
    }
}

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
    val zones: ZoneDistribution,
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
