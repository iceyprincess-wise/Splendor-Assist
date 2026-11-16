package com.assistant.runtime

enum class ActionClass { PASS, SHOT, CROSS, DEFEND, EVADE, KEEPER, MOVE, NONE }
enum class EngineCapability { ATTACK, DEFENSE, MOVEMENT, PASSING, KEEPER, SUPPORT }

/* Immutable per-capture snapshot. The only frame input a gameplay engine may read; engines may also consult world state and device buses. */
/* Per-zone body count. Owned by FrameAssembler; read-only to engines. */
data class ZoneDistribution(
    val leftOurs: Int = 0,  val leftTheirs: Int = 0,
    val midOurs: Int = 0,   val midTheirs: Int = 0,
    val rightOurs: Int = 0, val rightTheirs: Int = 0
) {
    // RF-D5: the old else-branch mapped ANY z outside {0, 1} to the right
    // zone. No caller passes out-of-range today (OverloadPlaystyleContributor
    // and PressEvadeContributor only pass 0..2), so this is a no-op for live
    // data - but a stray zone must read as empty, not as the right zone.
    fun oursIn(z: Int) = when (z) { 0 -> leftOurs; 1 -> midOurs; 2 -> rightOurs; else -> 0 }
    fun theirsIn(z: Int) = when (z) { 0 -> leftTheirs; 1 -> midTheirs; 2 -> rightTheirs; else -> 0 }
    fun balanceIn(z: Int): Float {
        val o = oursIn(z); val x = theirsIn(z); val tot = o + x
        return if (tot > 0) ((o - x).toFloat() / tot).coerceIn(-1f, 1f) else 0f
    }
}

data class RuntimeEntity(
    val id: Int, val x: Float, val y: Float,
    val velocityX: Float, val velocityY: Float,
    val confidence: Float, val teammate: Boolean,
    val velocityXPerSecond: Float = 0f, val velocityYPerSecond: Float = 0f
)

data class RuntimeFrame(
    val frameId: Long,
    val timestampMs: Long,
    val hasBall: Boolean,
    val ballX: Float,
    val ballY: Float,
    val ballVelocityX: Float = 0f,
    val ballVelocityY: Float = 0f,
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
    val panic: Boolean,
    /*
     * Task C item (d): REAL goal-frame data from the goal detector, carried
     * through the frame so SHOT/CROSS contributors never have to fabricate
     * a target. Coords may be NON-ZERO when goalDetected=false: GoalDetector
     * returns the best blob's box even below MIN_GOAL_PIXELS, and
     * FrameAssembler copies the coords unconditionally - contributors must
     * gate on goalDetected, never on the coordinates. (RF-D3: the old
     * "All zeros + goalDetected=false" comment was false.)
     */
    val goalDetected: Boolean = false,
    val goalLeftX: Float = 0f,
    val goalRightX: Float = 0f,
    val goalTopY: Float = 0f,
    val goalBottomY: Float = 0f,
    val goalConfidence: Float = 0f,
    val goalkeeperVisible: Boolean = false,
    val goalkeeperX: Float = 0f,
    val goalkeeperY: Float = 0f,
    val opticalVariance: Float = 0f,
    val entities: List<RuntimeEntity> = emptyList(),
    val ownerId: Int? = null,
    val defenderId: Int? = null,
    val attackDirection: Int = 0,
    val captureWidth: Float = 0f,
    val captureHeight: Float = 0f,
    val capturedAtNanos: Long = 0L,
    val predictedAtNanos: Long = 0L,
    val ballVelocityXPerSecond: Float = 0f,
    val ballVelocityYPerSecond: Float = 0f,
    val passTargetId: Int? = null
) {
    // RF-D1/RF-D2: the old gate trusted a frame with a 0x0 capture size
    // (reachable before the camera profile populates), a confidence above
    // 1, a NaN entity, or NaN goal coords on a detected goal. Timestamps
    // stay unchecked on purpose: 0 reads as ancient and stale-rejects,
    // which is the safe direction.
    val trusted: Boolean get() = enabled && confidence.isFinite() && confidence > 0f && confidence <= 1f &&
        ballX.isFinite() && ballY.isFinite() && ballVelocityX.isFinite() &&
        ballVelocityY.isFinite() && opticalVariance.isFinite() &&
        captureWidth.isFinite() && captureWidth > 0f &&
        captureHeight.isFinite() && captureHeight > 0f &&
        entities.all { it.x.isFinite() && it.y.isFinite() && it.velocityX.isFinite() &&
            it.velocityY.isFinite() && it.velocityXPerSecond.isFinite() &&
            it.velocityYPerSecond.isFinite() && it.confidence.isFinite() } &&
        (!goalDetected || (goalLeftX.isFinite() && goalRightX.isFinite() &&
            goalTopY.isFinite() && goalBottomY.isFinite() && goalConfidence.isFinite()))
    val goalCenterX: Float get() = (goalLeftX + goalRightX) * 0.5f
    val goalCenterY: Float get() = (goalTopY + goalBottomY) * 0.5f
    // RF-D4: width 0 means "no usable box" (inverted or empty box fails safe).
    val goalWidth: Float get() = (goalRightX - goalLeftX).coerceAtLeast(0f)
}

data class EngineContribution(
    val engine: String,
    val actionClass: ActionClass,
    val targetX: Float,
    val targetY: Float,
    val authority: Float,
    val confidence: Float,
    val durationHintMs: Long,
    // Slot 0 preserves legacy routing. Explicit intents require a trained slot.
    val controlSlot: Int = 0,
    val aimOriginX: Float? = null,
    val aimOriginY: Float? = null,
    val aimEntityId: Int? = null,
    val targetEntityId: Int? = null
) {
    val weight: Float
        get() = authority.coerceIn(0f, 1f) * confidence.coerceIn(0f, 1f)
}

/* Uniform contract. Upgrade an engine's internals freely; this never changes. */
interface GameplayContributor {
    // SPLENDOR_V43_FAST_GATE: O(1) early culling to prevent heavy math on discarded frames
    fun fastGate(frame: RuntimeFrame): Boolean = true
    val engineName: String
    val capabilities: Set<EngineCapability>
    fun initialize() {}
    fun warmUp() {}
    fun update(frame: RuntimeFrame) {}
    fun contribute(frame: RuntimeFrame): EngineContribution?
    fun reset() {}
    fun shutdown() {}
}
