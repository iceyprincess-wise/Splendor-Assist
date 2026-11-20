package com.assistant.runtime

enum class ActionClass { PASS, SHOT, CROSS, DEFEND, EVADE, KEEPER, MOVE, NONE, THROUGH }
enum class EngineCapability { ATTACK, DEFENSE, MOVEMENT, PASSING, KEEPER, SUPPORT }

/**
 * D8 (Ed, §5): the four cross types the frame must distinguish, each with
 * its own target (crossTargetX/Y). PASS = cross as a pass to a receiver;
 * SHOT = cross to a shooter; HEAD = cross to a header in the box;
 * OPPONENT_PUNCH = a cross from the opponent, which the keeper punches
 * (target = the goal centre). NONE = no cross is on. The old frame
 * carried one crossing-lane target for every case; that conflation is
 * what D8 ends.
 */
enum class CrossType { NONE, PASS, SHOT, HEAD, OPPONENT_PUNCH }

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

/** B4: one low-block movement target, addressed to a defender by id. */
data class LowBlockMovement(
    val defenderId: Int,
    val targetX: Float,
    val targetY: Float
)

/**
 * G-C match state (batch 4, Ed D12): the match clock/score phase the
 * OCR already produced, copied into the frame by FrameAssembler.
 * UNKNOWN = no score+minute pair has ever been parsed.
 */
enum class MatchPhase { UNKNOWN, EARLY, MID, LATE, FULLTIME }

/**
 * G-F marking assignment (batch 4): one defender marking one
 * opponent, at a goal-side point next to the man. overloadSecondary
 * marks the SECOND marker on a G-O 2v1 (published, not driven — no
 * second-player control exists; see OverloadMarkingEngine).
 */
data class MarkingAssignment(
    val defenderId: Int,
    val opponentId: Int,
    val targetX: Float,
    val targetY: Float,
    val overloadSecondary: Boolean = false
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
    val passTargetId: Int? = null,
    /*
     * L10 Learning-Chain Reader: the learning/tactical scalars
     * LearningChainReader copies out of Phase3WorldStateStore. Every
     * value defaults to "no learning yet" (0 / false / tickSync 1.0) and
     * every consumer gates on the matching confidence, so an unlearned
     * frame behaves exactly as before. These are advisory inputs, not
     * trust inputs — `trusted` below deliberately does not check them.
     */
    val opponentAggression: Float = 0f,
    val opponentPressFrequency: Float = 0f,
    val opponentBehaviourConfidence: Float = 0f,
    val playerPassBias: Float = 0f,
    val playerDribbleBias: Float = 0f,
    val playerShootBias: Float = 0f,
    val playerTendencyConfidence: Float = 0f,
    val shootingHabitConfidence: Float = 0f,
    val formationAdaptationScore: Float = 0f,
    val formationStable: Boolean = false,
    val formationConfidence: Float = 0f,
    val offsideRisk: Float = 0f,
    val offsideSafeLanes: Int = 0,
    val counterattackDetected: Boolean = false,
    val counterattackConfidence: Float = 0f,
    val preferredPassingConfidence: Float = 0f,
    val temporalConfidence: Float = 0f,
    val onlineAdaptationGain: Float = 0f,
    val tickSyncMultiplier: Float = 1f,
    /*
     * D8 four cross types: the cross kind on this frame + its own target.
     * passTarget (crossing-lane target) is unchanged; crossTarget is the
     * type's target (for OPPONENT_PUNCH: the goal centre, the punch target).
     */
    val crossType: CrossType = CrossType.NONE,
    val crossTargetX: Float = 0f,
    val crossTargetY: Float = 0f,
    /*
     * L13 Defensive Stance Producer: the tight line/midfield stance
     * VisionCore computed from computeUserLine. dragOutHeld names a
     * drag-out the stance refused to follow (D14).
     */
    val defensiveLineX: Float = 0f,
    val defensiveLineSpread: Float = 0f,
    val stanceTight: Boolean = false,
    val dragOutHeld: Boolean = false,
    /*
     * L14 Home Position Producer: the tracked defender's home on the
     * tight line. homeFound=false means "no line — use the historical
     * screen-centre fallback" (DefenseContributor).
     */
    val homeX: Float = 0f,
    val homeY: Float = 0f,
    val homeFound: Boolean = false,
    /*
     * B5 User-Line Publisher: the raw user line (DefensiveLineEngine.
     * computeUserLine, published to the world store by VisionCore) —
     * the stance above is derived from it. userLineFound=false means
     * no user line was computed this frame.
     */
    val userLineX: Float = 0f,
    val userLineSpread: Float = 0f,
    val userLineFound: Boolean = false,
    /*
     * B4 Low-Block Wiring: the low-block movement list, one target per
     * defender (by defender id). lowBlockActive is false while QD-3
     * (Q68-27, Y→X axis rework) is unresolved — the movements are
     * published and observable, but no consumer applies them until Ed
     * defines the X-geometry, or the wired positions are wrong.
     */
    val lowBlockMovements: List<LowBlockMovement> = emptyList(),
    val lowBlockActive: Boolean = false,
    /*
     * D13 Through-Pass Sanitizer wiring: the user's through-press
     * state on this frame. throughPressed is a CANDIDATE (trainer
     * pixel-occlusion + touch signal), not a confirmed hardware press
     * — see ControlMappingTrainer / ThroughPassSanitizerGateway.
     * throughReceiver/throughLead are the B9 direction-aware lead's
     * target; throughLaneViable names a real viable through lane.
     */
    val throughPressed: Boolean = false,
    val throughReceiverX: Float = 0f,
    val throughReceiverY: Float = 0f,
    val throughLeadX: Float = 0f,
    val throughLeadY: Float = 0f,
    val throughLaneViable: Boolean = false,
    /*
     * G-C Match-State Engine (batch 4, D12 zero-added-load): the
     * score/clock the OCR already produced, parsed by
     * MatchStateEngine and copied here by FrameAssembler. All default
     * to "unknown" (minute 0, UNKNOWN phase, known=false) — an
     * unparsed frame behaves exactly as before; consumers gate on
     * matchStateKnown. ShotContributor reads these for the chase /
     * protect-lead posture.
     */
    val matchMinute: Int = 0,
    val matchScoreFor: Int = 0,
    val matchScoreAgainst: Int = 0,
    val matchPhase: MatchPhase = MatchPhase.UNKNOWN,
    val matchStateKnown: Boolean = false,
    /*
     * G-F Marking Engine + G-O Overload plan (batch 4): the marking
     * assignments FrameAssembler computed from the tracked entities.
     * overloadOpponentId names the 2v1 target when one exists;
     * overloadSecondaryId names its second marker — PUBLISHED ONLY.
     * No second-player control mechanism exists in the repo, so the
     * secondary marker is planned and observable, never driven
     * (OverloadMarkingEngine documents the blocker).
     */
    val markingAssignments: List<MarkingAssignment> = emptyList(),
    val overloadOpponentId: Int? = null,
    val overloadSecondaryId: Int? = null,
    /*
     * Q68-80 (batch 5b): the overload-balance fields the overload engines
     * already compute — centralControl (CentralOverloadDetectionResult),
     * left/rightWingAdvantage (WingOverloadDetectionResult) — had ZERO
     * production readers (only a test read the wing advantages). They are
     * copied here by FrameAssembler and exposed in frameRuntimeSnapshot(),
     * so the live frame state names which side the overload favours.
     * Defaults 0f = no measured advantage, exactly as before.
     */
    val centralControl: Float = 0f,
    val leftWingAdvantage: Float = 0f,
    val rightWingAdvantage: Float = 0f
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
    val targetEntityId: Int? = null,
    /*
     * G-M Physical Barge (batch 4, Q68-31): a physical barge request.
     * Default false — all 55 pre-existing contributors are unaffected.
     * RuntimeDecisionLoop.chooseRequest renders barge=true as a short,
     * hard swipe (duration clamped to <= 20 ms); that clamp IS the
     * terminal support — the terminal dispatches swipes, and a barge is
     * a short hard swipe, distinguished from an ordinary DEFEND drag
     * by this flag.
     */
    val barge: Boolean = false
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
    // RF-D7 (batch 5): the shutdown() hook is removed — it had no caller
    // and no override in any of the 55 contributors. Teardown is reset()
    // via GameplayEngineRegistry.resetAll(), wired through
    // RuntimeCoordinator.shutdown() (MainActivity Stop tap and
    // OverlayService.onDestroy).
}
