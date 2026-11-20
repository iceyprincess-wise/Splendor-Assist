package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Decoy-run engine (queue item 3, Ed 2026-11-20 ~14:58 WAT).
 *
 * The tactic: when the opponent's offside line is HIGH and a defender can
 * be dragged, the controlled player (the ball carrier) makes a false run
 * that pulls the pressing defender off the safe passing lane — then the
 * pass goes to the lane the offside engine scores safe.
 *
 * TRACE (harness-batch-decoy/TRACE.md):
 * - VisionCore calls OffsideRiskEstimationEngine.analyze() with NO
 *   lastDefenderX, so the stored offside result uses the fixed default
 *   and cannot discriminate lanes (every receiver is "safe" in capture
 *   px). This engine therefore re-runs analyze() with a REAL line measured
 *   from the live scene (deepest non-keeper opponent along the attack
 *   axis) — arithmetic on measured positions, not a new detector.
 * - The pass/run path is the single-offer arbiter (ConAIEngine): a MOVE
 *   offer wins only when no tactical candidate has authority >= 0.30, so
 *   the drag fires exactly when no good immediate pass exists — which is
 *   when the decoy is needed. The AIM offer is an ordinary PASS candidate.
 * - G-L: no arbitration extension is built. The two-step play sequences
 *   across frames through this engine's own phase machine (IDLE → DRAG →
 *   AIM → IDLE), the same pattern CombinationPlay uses for its first step.
 * - ExecutionRequest is a joystick swipe: the app AIMS, it never presses
 *   Pass or Through (D13: through passes stay USER-ONLY). This engine
 *   offers MOVE and PASS only — never THROUGH.
 * - The engine is pure (no RuntimeLogger calls — android.util.Log is a
 *   stub that throws under the harness JVM). Live proof is the existing
 *   dispatch log, which carries the winning engine's name
 *   (engine=DecoyRun, no59 ITEM 1).
 * - No frame-rate constant anywhere: all horizons are per-frame positions
 *   or wall-ms timeouts from frame.timestampMs. The AIM target is the
 *   receiver's LIVE position via optimize(retention = 1.0f) — deliberately
 *   NOT optimizeWithAdvancedTactics, whose fixed frame-rate assumption
 *   over-leads 3x at the 20 fps processed rate (open TruePass defect,
 *   File 217).
 *
 * STATED CONSTANTS (tuning, not measured — flagged for on-device check):
 * every fraction below names its anchor in its comment. Nothing here
 * pretends to be a measured game constant.
 */
enum class DecoyPhase { IDLE, DRAG, AIM }

data class DecoyDecision(
    val phase: DecoyPhase,
    val viable: Boolean,
    val dragTargetX: Float,
    val dragTargetY: Float,
    val receiverId: Int?,
    val targetX: Float,
    val targetY: Float,
    val authority: Float,
    val risk: Float,
    val closedReason: String
)

object DecoyRunEngine {

    /** Space behind the offside line (line → attack-end edge) that counts
     *  as a HIGH line — a pocket worth running into. Same 0.10f class as
     *  CombinationPlayEngine.RUN_AHEAD_FRACTION_OF_WIDTH. */
    const val LINE_HIGH_GAP_FRACTION_OF_WIDTH = 0.10f

    /** A receiver ahead of the ball by this fraction of width is making a
     *  run, not a layoff (same value as CombinationPlayEngine). */
    const val RUN_AHEAD_FRACTION_OF_WIDTH = 0.10f

    /** The presser must be this close to the ball to be draggable — ~2x
     *  DribbleEngine's tackle radius (0.08f of height). Beyond it he
     *  cannot be pulled off the lane in time. */
    const val PRESSER_RADIUS_FRACTION_OF_HEIGHT = 0.15f

    /** Drag run length, as a fraction of the smaller capture side — a
     *  real run, not a step (DribbleEngine's step is 0.06f). */
    const val DRAG_DISTANCE_FRACTION_OF_MIN_SIDE = 0.15f

    /** Drag fan: directions searched around the attack direction. Wider
     *  than DribbleEngine's ±30 because a decoy must pull a man OFF a
     *  lane, not just find space. Never backwards by construction
     *  (cos(60°) > 0). */
    const val DRAG_FAN_DEGREES = 60
    const val DRAG_FAN_STEP_DEGREES = 15

    /** Presser displacement from drag start that proves he followed the
     *  carrier — 10x the sub-2 px/frame detection jitter floor
     *  (trajectory batch). */
    const val DRAG_CONFIRM_PX = 20f

    /** A drag longer than this stops being a decoy — the carrier is just
     *  dribbling into trouble. Wall ms from frame.timestampMs. */
    const val DRAG_TIMEOUT_MS = 2000L

    /** The aim window: the user presses pass within this of the lane
     *  opening, or the play is abandoned (and cools down). */
    const val AIM_TIMEOUT_MS = 2000L

    /** Quiet period per receiver after a completed/aborted play, so a
     *  failed decoy cannot re-trigger frame after frame. */
    const val COOLDOWN_MS = 1500L

    /** DRAG authority: beats a dribble (0.45/0.55) and a combination
     *  (0.55) — a decoy is a committed play — but the arbiter's tactical
     *  rule (>= 0.30 wins outright) still lets a clean pass outrank it. */
    const val DRAG_AUTHORITY = 0.60f

    // ---- play state (reset by reset(); single decision thread) ----
    private var phase = DecoyPhase.IDLE
    private var dragStartMs = 0L
    private var dragDefenderId = -1
    private var dragDefenderStartX = 0f
    private var dragDefenderStartY = 0f
    private var dragSegmentDistStart = 0f
    private var receiverId: Int? = null
    private var receiverRisk = 0f
    private var aimStartMs = 0L
    private var cooldownUntilMs = 0L
    private var cooldownReceiverId: Int? = null

    fun reset() {
        phase = DecoyPhase.IDLE
        dragStartMs = 0L
        dragDefenderId = -1
        dragDefenderStartX = 0f
        dragDefenderStartY = 0f
        dragSegmentDistStart = 0f
        receiverId = null
        receiverRisk = 0f
        aimStartMs = 0L
        cooldownUntilMs = 0L
        cooldownReceiverId = null
    }

    private fun none(reason: String) = DecoyDecision(
        DecoyPhase.IDLE, false, 0f, 0f, null, 0f, 0f, 0f, 0f, reason
    )

    /** Abort an in-progress play: back to IDLE with a cooldown on the
     *  receiver, so a failed decoy cannot re-trigger immediately. An
     *  IDLE rejection sets no cooldown. */
    private fun abort(nowMs: Long, reason: String): DecoyDecision {
        val wasActive = phase != DecoyPhase.IDLE
        val oldReceiver = receiverId
        reset()
        if (wasActive) {
            cooldownUntilMs = nowMs + COOLDOWN_MS
            cooldownReceiverId = oldReceiver
        }
        return none(reason)
    }

    /**
     * One frame's decoy decision. Pure over explicit inputs (no
     * singletons) so the harness can drive it frame by frame.
     *
     * @param scene the live scene (freshness is the contributor's gate,
     *   0..250 ms like TruePassContributor); null ends any play.
     * @param graph the passing graph of the same vision cycle.
     */
    fun decide(
        frame: RuntimeFrame,
        scene: SceneSnapshot?,
        graph: PassingLaneGraph
    ): DecoyDecision {
        val nowMs = frame.timestampMs
        if (!frame.trusted || !frame.hasBall) return abort(nowMs, "no-ball-or-untrusted")
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return abort(nowMs, "no-capture")
        if (scene == null) return abort(nowMs, "no-scene")

        val attackSign = if (frame.attackDirection < 0) -1f else 1f
        val w = frame.captureWidth

        // Fresh outfield opponents only — the same lastSeenFrame filter
        // FrameAssembler applies. The keeper is excluded: he does not
        // press the carrier upfield, and he is not the last FIELD defender.
        val opponents = scene.trackedPlayers.filter { p ->
            !p.isUserTeam && !p.isGoalkeeper &&
                p.lastSeenFrame == scene.frameNumber &&
                p.x.isFinite() && p.y.isFinite()
        }
        if (opponents.isEmpty()) return abort(nowMs, "no-line")

        // The REAL offside line: deepest outfield opponent along the
        // attack axis. VisionCore passes no lastDefenderX (fixed default)
        // — that default is NEVER used on this path.
        val lastDefender = opponents.maxByOrNull { it.x * attackSign }
            ?: return abort(nowMs, "no-line")
        val lineX = lastDefender.x

        // The line is HIGH when there is a real pocket behind it (line →
        // attack-end edge). A deep line (camped on the goal) leaves no
        // space to run into — no decoy.
        val spaceBehind = if (attackSign > 0f) w - lineX else lineX
        if (spaceBehind / w < LINE_HIGH_GAP_FRACTION_OF_WIDTH) {
            return abort(nowMs, "line-not-high")
        }
        // The ball must still be behind the line — past it, we are
        // already in behind and the decoy is pointless.
        if ((frame.ballX - lineX) * attackSign >= 0f) {
            return abort(nowMs, "ball-past-line")
        }

        // Score every lane against the REAL line (the stored offside
        // result was computed with the fixed default and cannot
        // discriminate — see TRACE §1). The scoring is mirrored into a
        // +x frame for a -x attack because analyze() assumes attack +x
        // (see analyzeWithRealLine); verdicts map back onto the original
        // lanes by receiver id, so aim targets stay in real coordinates.
        val offside = analyzeWithRealLine(graph, lineX, attackSign, w)
        val runAhead = RUN_AHEAD_FRACTION_OF_WIDTH * w

        return when (phase) {
            DecoyPhase.IDLE -> startIfTriggered(
                frame, offside, opponents, attackSign, runAhead, nowMs
            )
            DecoyPhase.DRAG -> continueDrag(
                frame, scene, offside, opponents, attackSign, nowMs
            )
            DecoyPhase.AIM -> continueAim(
                frame, scene, offside, nowMs
            )
        }
    }

    /**
     * Score the lanes against the real line. OffsideRiskEstimationEngine.
     * analyze() assumes attack +x (it compares receiver.x directly to
     * lastDefenderX: behind the line is rx < line - 15). For a -x attack
     * that comparison is backwards — a receiver onside in -x (rx > line)
     * would read as already past. So a -x attack mirrors the lane
     * geometry into a +x frame (x' = width - x, velocity flipped), scores
     * there, and maps the verdicts back onto the ORIGINAL lanes by
     * receiver id. The aim target therefore always stays in real
     * coordinates; only the risk/safe verdict crosses the mirror. A +x
     * attack calls analyze() directly (no transform, no allocation).
     */
    private fun analyzeWithRealLine(
        graph: PassingLaneGraph,
        lineX: Float,
        attackSign: Float,
        width: Float
    ): OffsideRiskEstimationResult {
        if (attackSign > 0f) return OffsideRiskEstimationEngine.analyze(graph, lineX)
        val mirrored = graph.lanes.map { lane ->
            PassingLane(
                passer = mirrorPlayer(lane.passer, width),
                receiver = mirrorPlayer(lane.receiver, width),
                distance = lane.distance,
                pressure = lane.pressure,
                blocked = lane.blocked,
                score = lane.score
            )
        }
        val scored = OffsideRiskEstimationEngine.analyze(
            PassingLaneGraph(mirrored), width - lineX
        )
        val byReceiver = scored.lanes.associateBy { it.lane.receiver.id }
        return OffsideRiskEstimationResult(
            graph.lanes.mapNotNull { lane ->
                val verdict = byReceiver[lane.receiver.id] ?: return@mapNotNull null
                OffsideRisk(lane, verdict.risk, verdict.safe)
            }
        )
    }

    private fun mirrorPlayer(p: TrackedPlayer, width: Float): TrackedPlayer =
        p.copy(x = width - p.x, velocityX = -p.velocityX)

    /** Lowest-risk safe lane whose receiver is making a forward run. */
    private fun pickLane(
        offside: OffsideRiskEstimationResult,
        frame: RuntimeFrame,
        attackSign: Float,
        runAhead: Float
    ): OffsideRisk? {
        var best: OffsideRisk? = null
        for (entry in offside.lanes) {
            if (!entry.safe) continue
            val rx = entry.lane.receiver.x
            if ((rx - frame.ballX) * attackSign <= runAhead) continue
            if (best == null || entry.risk < best.risk) best = entry
        }
        return best
    }

    private fun laneConfidence(lane: PassingLane): Float =
        (lane.score * (1f - lane.pressure)).coerceIn(0f, 1f)

    private fun laneIsGuarded(lane: PassingLane): Boolean =
        lane.blocked || laneConfidence(lane) < PassingLaneTargetProducer.MIN_CONFIDENCE

    /** The receiver's LIVE scene position when fresh, else the lane's
     *  receiver position (same vision cycle as the frame). */
    private fun liveReceiverPos(
        scene: SceneSnapshot,
        lane: PassingLane
    ): Pair<Float, Float> {
        val live = scene.trackedPlayers.firstOrNull { p ->
            p.id == lane.receiver.id && p.isUserTeam && !p.isGoalkeeper &&
                p.lastSeenFrame == scene.frameNumber
        }
        return if (live != null) live.x to live.y
        else lane.receiver.x to lane.receiver.y
    }

    private fun startIfTriggered(
        frame: RuntimeFrame,
        offside: OffsideRiskEstimationResult,
        opponents: List<TrackedPlayer>,
        attackSign: Float,
        runAhead: Float,
        nowMs: Long
    ): DecoyDecision {
        val lane = pickLane(offside, frame, attackSign, runAhead)
            ?: return none("no-safe-lane")
        // The decoy only exists when the safe lane is GUARDED — if it is
        // already open, TruePass aims at it and no drag is needed.
        if (!laneIsGuarded(lane.lane)) return none("lane-open")
        val receiver = lane.lane.receiver
        // Cooldown is per receiver: a failed play on this receiver cannot
        // re-trigger until it expires.
        if (receiver.id == cooldownReceiverId && nowMs < cooldownUntilMs) {
            return none("cooldown")
        }
        // The presser: nearest outfield opponent to the ball. He is the
        // man the carrier's run will pull off the lane.
        val presser = opponents.minByOrNull { p ->
            hypot(p.x - frame.ballX, p.y - frame.ballY)
        } ?: return none("no-presser")
        val presserDist = hypot(presser.x - frame.ballX, presser.y - frame.ballY)
        if (presserDist > PRESSER_RADIUS_FRACTION_OF_HEIGHT * frame.captureHeight) {
            return none("no-presser")
        }
        // Start the drag.
        phase = DecoyPhase.DRAG
        dragStartMs = nowMs
        dragDefenderId = presser.id
        dragDefenderStartX = presser.x
        dragDefenderStartY = presser.y
        dragSegmentDistStart = distancePointToSegment(
            presser.x, presser.y, frame.ballX, frame.ballY, receiver.x, receiver.y
        )
        receiverId = receiver.id
        receiverRisk = lane.risk
        val (tx, ty) = dragPoint(frame, receiver.x, receiver.y, attackSign)
        return DecoyDecision(
            DecoyPhase.DRAG, true, tx, ty, receiver.id,
            0f, 0f, DRAG_AUTHORITY, lane.risk, "drag"
        )
    }

    private fun continueDrag(
        frame: RuntimeFrame,
        scene: SceneSnapshot,
        offside: OffsideRiskEstimationResult,
        opponents: List<TrackedPlayer>,
        attackSign: Float,
        nowMs: Long
    ): DecoyDecision {
        val rid = receiverId ?: return abort(nowMs, "receiver-gone")
        if (nowMs - dragStartMs > DRAG_TIMEOUT_MS) return abort(nowMs, "drag-timeout")
        // The lane must still exist (same receiver, still in the graph).
        // Its offside state does NOT abort the drag: the receiver is
        // timing his run and may cross the line mid-drag — offside is
        // judged at the pass, which is the AIM phase's gate.
        val laneEntry = offside.lanes.firstOrNull { it.lane.receiver.id == rid }
            ?: return abort(nowMs, "receiver-gone")
        val lane = laneEntry.lane
        // SUCCESS 1 — the lane opened: viable per the producer's own bar.
        if (!lane.blocked && laneConfidence(lane) >= PassingLaneTargetProducer.MIN_CONFIDENCE) {
            return enterAim(frame, scene, laneEntry, nowMs)
        }
        // SUCCESS 2 — the presser followed the carrier: displaced from
        // his drag-start spot AND farther from the ball→receiver segment
        // than when the drag began. Either success alone opens the AIM.
        val presser = opponents.firstOrNull { it.id == dragDefenderId }
            ?: return abort(nowMs, "presser-gone")
        val displaced = hypot(presser.x - dragDefenderStartX, presser.y - dragDefenderStartY)
        if (displaced >= DRAG_CONFIRM_PX) {
            val segNow = distancePointToSegment(
                presser.x, presser.y, frame.ballX, frame.ballY, lane.receiver.x, lane.receiver.y
            )
            if (segNow > dragSegmentDistStart) {
                return enterAim(frame, scene, laneEntry, nowMs)
            }
        }
        // Keep dragging: the point is recomputed each frame from the
        // carrier's live position (a direction, not a fixed spot).
        val (tx, ty) = dragPoint(frame, lane.receiver.x, lane.receiver.y, attackSign)
        return DecoyDecision(
            DecoyPhase.DRAG, true, tx, ty, rid,
            0f, 0f, DRAG_AUTHORITY, receiverRisk, "drag"
        )
    }

    private fun enterAim(
        frame: RuntimeFrame,
        scene: SceneSnapshot,
        laneEntry: OffsideRisk,
        nowMs: Long
    ): DecoyDecision {
        phase = DecoyPhase.AIM
        aimStartMs = nowMs
        receiverRisk = laneEntry.risk
        // Offside is judged at the pass: the receiver may have crossed
        // the line mid-drag (the drag does not abort on that), so if he
        // is past it the moment the lane opens, the play enters AIM in
        // the HOLDING state — no pass is offered until he is onside
        // again (continueAim's gate). Offering here would put one frame
        // of PASS to an offside receiver through the arbiter.
        if (!laneEntry.safe) {
            return DecoyDecision(
                DecoyPhase.AIM, false, 0f, 0f, receiverId,
                0f, 0f, 0f, laneEntry.risk, "receiver-offside"
            )
        }
        return buildAimDecision(frame, scene, laneEntry)
    }

    /** The AIM offer: receiver's LIVE scene position (fresh) — no velocity
     *  lead on purpose (optimizeWithAdvancedTactics over-leads 3x at
     *  20 fps (open TruePass defect). The pass goes where he IS. */
    private fun buildAimDecision(
        frame: RuntimeFrame,
        scene: SceneSnapshot,
        laneEntry: OffsideRisk
    ): DecoyDecision {
        val rid = receiverId ?: return abort(frame.timestampMs, "receiver-gone")
        val (rx, ry) = liveReceiverPos(scene, laneEntry.lane)
        val target = TrueTargetPassingEngine.optimize(
            frame.ballX, frame.ballY, rx, ry, 1.0f
        )
        val authority = ((1f - laneEntry.risk) *
            laneConfidence(laneEntry.lane)).coerceIn(0f, 1f)
        return DecoyDecision(
            DecoyPhase.AIM, true, 0f, 0f, rid,
            target.correctedX, target.correctedY, authority,
            laneEntry.risk, "aim"
        )
    }

    private fun continueAim(
        frame: RuntimeFrame,
        scene: SceneSnapshot,
        offside: OffsideRiskEstimationResult,
        nowMs: Long
    ): DecoyDecision {
        val rid = receiverId ?: return abort(nowMs, "receiver-gone")
        if (nowMs - aimStartMs > AIM_TIMEOUT_MS) return abort(nowMs, "aim-timeout")
        val laneEntry = offside.lanes.firstOrNull { it.lane.receiver.id == rid }
            ?: return abort(nowMs, "receiver-gone")
        // Offside is judged at the pass: while the receiver is past the
        // line the app offers nothing (the user can still pass manually);
        // the play stays in AIM until he is onside again or it times out.
        if (!laneEntry.safe) {
            return DecoyDecision(
                DecoyPhase.AIM, false, 0f, 0f, rid,
                0f, 0f, 0f, laneEntry.risk, "receiver-offside"
            )
        }
        receiverRisk = laneEntry.risk
        return buildAimDecision(frame, scene, laneEntry)
    }

    /**
     * Drag point: fan search around the attack direction (DribbleEngine
     * pattern), scored by separation from the ball→receiver lane plus
     * attack progress — the direction that pulls the presser farthest off
     * the lane while keeping the attack moving forward.
     */
    private fun dragPoint(
        frame: RuntimeFrame,
        receiverX: Float,
        receiverY: Float,
        attackSign: Float
    ): Pair<Float, Float> {
        val dragDist = DRAG_DISTANCE_FRACTION_OF_MIN_SIDE *
            minOf(frame.captureWidth, frame.captureHeight)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestX = frame.ballX
        var bestY = frame.ballY
        var deg = -DRAG_FAN_DEGREES
        while (deg <= DRAG_FAN_DEGREES) {
            val rad = Math.toRadians(deg.toDouble()).toFloat()
            val dx = attackSign * cos(rad)
            val dy = sin(rad)
            val tx = (frame.ballX + dx * dragDist).coerceIn(0f, frame.captureWidth)
            val ty = (frame.ballY + dy * dragDist).coerceIn(0f, frame.captureHeight)
            val separation = distancePointToSegment(
                tx, ty, frame.ballX, frame.ballY, receiverX, receiverY
            )
            val progress = (tx - frame.ballX) * attackSign
            val score = separation + progress * 0.5f
            if (score > bestScore) {
                bestScore = score
                bestX = tx
                bestY = ty
            }
            deg += DRAG_FAN_STEP_DEGREES
        }
        return bestX to bestY
    }

    /** Distance from point P to segment AB (clamped projection). */
    private fun distancePointToSegment(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 0f) return hypot(px - ax, py - ay)
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = t.coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
