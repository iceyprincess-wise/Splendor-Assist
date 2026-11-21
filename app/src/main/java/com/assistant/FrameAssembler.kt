package com.assistant

// F49_PATCH_V1






































import com.assistant.runtime.*





import com.assistant.runtime.RuntimeFrame
import com.assistant.runtime.ZoneDistribution

















import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*




























internal const val GAMEPLAY_DECISION_ENGINE_RAPID_EXECUTION_TAG = "GameplayDecisionEngine.rapidPrime"

internal const val GAMEPLAY_DECISION_ENGINE_PRIME_AUTHORITY_TAG = "GameplayDecisionEngine.prime"

internal const val MAX_TELEMETRY_AGE_MS = 250L

internal const val SERVER_TICK_RATE_MS = 33.33333f // 30Hz Authoritative Tick Window

internal const val DISPLAY_REFRESH_120HZ_MS = 8.333333f

// No68-C-D15: prediction horizon (see assemble()). 50 ms ahead of the
// measured pipeline latency, capped at 100 ms total by the leadMs clamp.
internal const val PREDICTION_LEAD_MS = 50f

internal const val ADHESION_COEFFICIENT = 1.25f       // Magnetic retention pull factor

internal const val DRIFT_FILTER_BETA = 0.90f          // Jitter filter strength

internal const val MIN_PULSE_INTERVAL_NS = 7_000_000L // ~142Hz micro-tick limit (7ms)

internal const val OMEGA_TURNING_THRESHOLD = 6.0f     // Joystick displacement speed threshold


object FrameAssembler {

    private val predictor = com.assistant.runtime.PredictiveState()
    private var predictionLayout: Pair<Float, Float>? = null
    private val frameCounter = AtomicLong(0L)

    @Volatile private var lastFrame: RuntimeFrame? = null
    @Volatile private var lastTrustRejectLogMs: Long = 0L

    fun assemble(opticalVariance: Float = 0f, capturedAtNanos: Long = System.nanoTime()): RuntimeFrame {
        val id = frameCounter.incrementAndGet()

        val world = Phase3WorldStateStore.current()
        val scene = SceneTracker.current().takeIf {
            System.currentTimeMillis() - it.timestamp in 0L..MAX_TELEMETRY_AGE_MS
        }
        val lanes = if (scene != null) world.crossingLaneAnalysis.lanes else emptyList()
        val laneCount = lanes.size
        val viable = lanes.count { it.viable }
        // The crossing best lane is now the CROSSING target only (D8):
        // crossTarget below reads it. passTarget moved to the passing
        // graph (B8) — a pass aims at the receiver, not 40 px above them.
        val bestLane = lanes.firstOrNull { it.viable }
        val bestConf = bestLane?.confidence ?: 0f
        // B8: the pass target comes from the PASSING-lane analysis
        // (PassingLaneTargetProducer over world.passingGraph), not from
        // the crossing lanes. No −40 crossing lead is applied.
        val passingTarget = if (scene != null) PassingLaneTargetProducer.targetFor(world.passingGraph) else null
        val passTargetX = passingTarget?.targetX ?: 0f
        val passTargetY = passingTarget?.targetY ?: 0f

        val telemetry = TelemetryRepository.current().takeIf { snapshot ->
            // B2 / Q68-54: ball freshness reads the SPLIT ball
            // timestamp — the shared stamp used to be refreshed by
            // keeper writes, so a stale ball read as fresh.
            val ballAgeMs = System.currentTimeMillis() -
                (snapshot.ballTimestampMs.takeIf { ts -> ts > 0L } ?: snapshot.timestamp)
            ballAgeMs in 0L..MAX_TELEMETRY_AGE_MS
        }
        val ballX = telemetry?.ballX ?: 0f
        val ballY = telemetry?.ballY ?: 0f
        val ballVx = telemetry?.ballVelocityX ?: 0f
        val ballVy = telemetry?.ballVelocityY ?: 0f
        val ballTrustNow = VisionTrust.ballTrust()
        val ballSeen = telemetry != null && telemetry.confidence > 0f &&
            scene?.trackedBallVisible == true && ballTrustNow > 0f && ballX.isFinite() && ballY.isFinite()

        /*
         * ROOT-CAUSE FIX (round 2): possession, not visibility.
         * BallPossessionEngine already computes true ownership every vision
         * cycle; the frame just never consumed it.
         */
        val possession = try {
            Phase3WorldStateStore.current().possession
        } catch (_: Throwable) { null }
        val possessionMinConf = 0.20f
        val hasBall =
            if (possession != null && possession.confidence >= possessionMinConf) {
                ballSeen && possession.hasPossession
            } else {
                false // Visibility alone does not establish possession.
            }

        val rawPlayers = scene?.trackedPlayers.orEmpty().filter {
            it.lastSeenFrame == scene?.frameNumber && it.x.isFinite() && it.y.isFinite() &&
                it.velocityX.isFinite() && it.velocityY.isFinite() &&
                it.confidence.isFinite() && it.confidence > 0f
        }

        // V6 VISION GUARD (field-proven over-count: players=30/opponents=30):
        // cap each side to 11 before zones/density/trust so downstream engines
        // never consume impossible head-counts.
        val assemblerPool = java.util.ArrayList<com.assistant.TrackedPlayer>(22)
        var oursCount = 0
        var theirsCount = 0
        for (p in rawPlayers) {
            if (p.isUserTeam) {
                if (oursCount < 11) { assemblerPool.add(p); oursCount++ }
            } else {
                if (theirsCount < 11) { assemblerPool.add(p); theirsCount++ }
            }
        }
        val players = assemblerPool
        val opponents = theirsCount

        // Real per-zone counts from tracked player positions (landscape thirds).
        val pitchH = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        var lo = 0; var lt = 0; var mo = 0; var mt = 0; var ro = 0; var rt = 0
        for (pl in players) {
            val mine = pl.isUserTeam
            when {
                pl.y < pitchH * 0.33f -> if (mine) lo++ else lt++
                pl.y > pitchH * 0.67f -> if (mine) ro++ else rt++
                else -> if (mine) mo++ else mt++
            }
        }
        val zones = ZoneDistribution(lo, lt, mo, mt, ro, rt)

        val enabled = try { SmartAssistRepository.enabled() } catch (_: Throwable) { false }
        val panic = try { SmartAssistRepository.panicActive() } catch (_: Throwable) { false }

        // GAP3: lane spread = share of lanes that are actually viable
        VisionTrust.stampLaneSpread(
            if (laneCount > 0) viable.toFloat() / laneCount.toFloat() else 0f
        )

        // Confidence follows the fresh ball sighting; lanes can only raise it.
        val sceneConfidence = scene?.confidence?.takeIf { it.isFinite() } ?: 0f
        val rawConfidence = maxOf(bestConf, if (ballSeen) ballTrustNow else 0f,
            if (players.isNotEmpty()) sceneConfidence else 0f).coerceIn(0f, 1f)
        val automaticTrustedFrame = VisionTrust.frameTrusted(players.size, opponents)
        val isTrustedFrame = OverrideStore.resolve("TRUST", automaticTrustedFrame)
        val confidence = if (isTrustedFrame && ballSeen) rawConfidence else 0f

        // N3 (f58): the 2026-11-18 session exited 877/947 frames as idle-untrusted
        // with no record of which trust leg failed. Log the verdict
        // components, throttled to one line per second (same cadence guard as
        // VisionTrust.tickAndLog), so the next session names the leg:
        // ballSeen (telemetry/scene freshness, trackedBallVisible),
        // isTrustedFrame (frameTrusted + TRUST override), or confidence.
        // No gate changes: this is observation only.
        try {
            val nowTrustMs = System.currentTimeMillis()
            if (!isTrustedFrame || !ballSeen) {
                if (nowTrustMs - lastTrustRejectLogMs >= 1000L) {
                    lastTrustRejectLogMs = nowTrustMs
                    com.assistant.diagnostic.RuntimeLogger.log(
                        "TRUST_REJECT ballSeen=$ballSeen trustedFrame=$isTrustedFrame " +
                            "ballTrust=${String.format("%.3f", ballTrustNow)} " +
                            "players=${players.size} opponents=$opponents " +
                            "optVar=${String.format("%.3f", opticalVariance)}",
                        "VISIONTRUST"
                    )
                }
            }
        } catch (_: Throwable) { }

        VisionTrust.tickAndLog()

        val goalDetected =
            (scene?.goalDetected ?: false) &&
                (scene?.goalConfidence ?: 0f) > 0f &&
                (scene?.goalRightX ?: 0f) > (scene?.goalLeftX ?: 0f) &&
                (scene?.goalBottomY ?: 0f) > (scene?.goalTopY ?: 0f)

        // L10 (batch 1): the learning-chain readout — the first read of
        // the learning/tactical results into the frame (repo map G2: the
        // assembler used to read only crossingLaneAnalysis + possession).
        val learning = try {
            LearningChainReader.read(world)
        } catch (_: Throwable) { LearningReadout() }

        // D8 (batch 1): the four cross types, each with its own target.
        // OPPONENT_PUNCH fires when we do NOT have the ball and it is
        // wide (an opponent cross the keeper punches; target = goal
        // centre). SHOT fires when we have the ball and a shooting lane
        // is viable (cross to a shooter; target = goal centre). HEAD
        // fires when the best crossing lane's receiver is inside the
        // goal span (cross to a header; target = the lane target). PASS
        // is the remaining viable crossing lane. NONE when no case holds.
        // passTarget is unchanged; crossTarget is the type's own target.
        val shootingViable = try {
            world.shootingLaneAnalysis.lanes.any { it.viable }
        } catch (_: Throwable) { false }
        val goalLeft = scene?.goalLeftX ?: 0f
        val goalRight = scene?.goalRightX ?: 0f
        val goalTop = scene?.goalTopY ?: 0f
        val goalBottom = scene?.goalBottomY ?: 0f
        val goalCentreX = (goalLeft + goalRight) * 0.5f
        val goalCentreY = (goalTop + goalBottom) * 0.5f
        val captureW = com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val captureH = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        val ballWide = ballY < pitchH * 0.33f || ballY > pitchH * 0.67f
        val receiverX = bestLane?.lane?.receiver?.x ?: Float.NaN
        val receiverInBox = goalDetected && receiverX.isFinite() &&
            receiverX >= goalLeft - captureW * 0.12f &&
            receiverX <= goalRight + captureW * 0.12f
        val (crossType, crossTargetX, crossTargetY) = when {
            !hasBall && ballWide -> Triple(
                CrossType.OPPONENT_PUNCH,
                if (goalDetected) goalCentreX else captureW * 0.5f,
                if (goalDetected) goalCentreY else captureH * 0.5f
            )
            hasBall && shootingViable -> Triple(
                CrossType.SHOT,
                if (goalDetected) goalCentreX else bestLane?.targetX ?: passTargetX,
                if (goalDetected) goalCentreY else bestLane?.targetY ?: passTargetY
            )
            hasBall && bestLane != null && receiverInBox -> Triple(
                // HEAD: the cross target is the CROSSING lane's target
                // (receiver.y − 40), not the passing target (B8).
                CrossType.HEAD, bestLane.targetX, bestLane.targetY
            )
            hasBall && bestLane != null -> Triple(
                CrossType.PASS, bestLane.targetX, bestLane.targetY
            )
            else -> Triple(CrossType.NONE, 0f, 0f)
        }

        val observedFrame = RuntimeFrame(
            frameId = scene?.frameNumber ?: id,
            timestampMs = System.currentTimeMillis(),
            hasBall = hasBall,
            ballX = ballX,
            ballY = ballY,
            ballVelocityX = ballVx,
            ballVelocityY = ballVy,
            playerCount = players.size,
            opponentCount = opponents,
            laneCount = laneCount,
            viableLaneCount = viable,
            passTargetX = passTargetX,
            passTargetY = passTargetY,
            bestLaneConfidence = bestConf,
            defenderDensity = if (players.isNotEmpty())
                opponents.toFloat() / players.size else 0f,
            zones = zones,
            confidence = confidence,
            enabled = enabled,
            panic = panic,
            goalDetected = goalDetected,
            goalLeftX = scene?.goalLeftX ?: 0f,
            goalRightX = scene?.goalRightX ?: 0f,
            goalTopY = scene?.goalTopY ?: 0f,
            goalBottomY = scene?.goalBottomY ?: 0f,
            goalConfidence = scene?.goalConfidence ?: 0f,
            goalkeeperVisible = scene?.goalkeeperVisible ?: false,
            goalkeeperX = scene?.goalkeeperX ?: 0f,
            goalkeeperY = scene?.goalkeeperY ?: 0f,
            opticalVariance = opticalVariance.coerceIn(0f, 1f),
            entities = players.map { com.assistant.runtime.RuntimeEntity(it.id, it.x, it.y,
                it.velocityX, it.velocityY, it.confidence, it.isUserTeam,
                // B6: per-player px/s (SceneTracker / PlayerMotionProducer).
                it.velocityXPerSecond, it.velocityYPerSecond, it.isGoalkeeper) },
            ownerId = world.ownership.takeIf { scene != null && it.hasOwner && it.confidence >= 0.2f }?.owner?.id,
            defenderId = world.defender.takeIf { scene != null && it.found }?.defender?.id,
            // L10: the learning-chain readout (see above).
            opponentAggression = learning.opponentAggression,
            opponentPressFrequency = learning.opponentPressFrequency,
            opponentBehaviourConfidence = learning.opponentBehaviourConfidence,
            playerPassBias = learning.playerPassBias,
            playerDribbleBias = learning.playerDribbleBias,
            playerShootBias = learning.playerShootBias,
            playerTendencyConfidence = learning.playerTendencyConfidence,
            shootingHabitConfidence = learning.shootingHabitConfidence,
            formationAdaptationScore = learning.formationAdaptationScore,
            formationStable = learning.formationStable,
            formationConfidence = learning.formationConfidence,
            offsideRisk = learning.offsideRisk,
            offsideSafeLanes = learning.offsideSafeLanes,
            counterattackDetected = learning.counterattackDetected,
            counterattackConfidence = learning.counterattackConfidence,
            preferredPassingConfidence = learning.preferredPassingConfidence,
            temporalConfidence = learning.temporalConfidence,
            onlineAdaptationGain = learning.onlineAdaptationGain,
            tickSyncMultiplier = learning.tickSyncMultiplier,
            // D8: the cross type + its own target (see above).
            crossType = crossType,
            crossTargetX = crossTargetX,
            crossTargetY = crossTargetY,
            // L13: the tight defensive stance (VisionCore).
            defensiveLineX = world.defensiveStance.lineX,
            defensiveLineSpread = world.defensiveStance.lineSpread,
            stanceTight = world.defensiveStance.tight,
            dragOutHeld = world.defensiveStance.dragOutHeld,
            // Queue item 9 (L1): the vacated slot X (0f when no drag-out).
            dragOutGapX = world.defensiveStance.dragOutGapX,
            // L14: the tracked defender's home on the tight line.
            homeX = world.defenderHome.x,
            homeY = world.defenderHome.y,
            homeFound = world.defenderHome.found,
            // B5: the raw user line (VisionCore → world store).
            userLineX = world.userLine.averageX,
            userLineSpread = (world.userLine.maxX - world.userLine.minX).coerceAtLeast(0f),
            userLineFound = world.userLine.found,
            // Midfield (queue item 5, batch midfield): the measured line
            // + its compactness, and the stance-derived fallback.
            midfieldLineX = world.midfieldLine.averageX,
            midfieldMinX = world.midfieldLine.minX,
            midfieldMaxX = world.midfieldLine.maxX,
            midfieldSpread = (world.midfieldLine.maxX - world.midfieldLine.minX).coerceAtLeast(0f),
            midfieldFound = world.midfieldLine.found,
            midfieldCompactness = world.midfieldCompactnessResult.compactness,
            midfieldConfidence = world.midfieldCompactnessResult.confidence,
            stanceMidfieldX = world.defensiveStance.midfieldX,
            stanceMidfieldSpread = world.defensiveStance.midfieldSpread,
            stanceMidfieldFound = world.defensiveStance.found,
            // B4: the low-block movement list. Queue item 9 (L2) resolved
            // QD-3: the gate is the engine's own active flag (ball deep
            // on the X axis, own goal known), not list non-emptiness —
            // the list is non-empty even while inactive (hold positions),
            // so isNotEmpty() would have made the block "active" on
            // every frame with defenders tracked.
            lowBlockMovements = world.lowBlockMovements,
            lowBlockActive = LowBlockContainmentEngine.QD3_AXIS_RESOLVED && world.lowBlockActive,
            // D13: the user's through-press state + the B9 lead target.
            // throughPressed is a candidate, not a confirmed press.
            throughPressed = ControlMappingTrainer.throughPressCandidateFresh(),
            throughReceiverX = world.throughBallAnalysis.lanes.firstOrNull { it.viable }?.lane?.receiver?.x ?: 0f,
            throughReceiverY = world.throughBallAnalysis.lanes.firstOrNull { it.viable }?.lane?.receiver?.y ?: 0f,
            throughLeadX = world.throughBallAnalysis.lanes.firstOrNull { it.viable }?.leadX ?: 0f,
            throughLeadY = world.throughBallAnalysis.lanes.firstOrNull { it.viable }?.leadY ?: 0f,
            throughLaneViable = world.throughBallAnalysis.lanes.any { it.viable },
            attackDirection = AttackAxisModel.directionIfConfident(),
            captureWidth = com.assistant.vision.CameraProfile.captureWidth.toFloat(),
            captureHeight = com.assistant.vision.CameraProfile.captureHeight.toFloat()
        )
        val now = System.nanoTime()
        // No68-C-D15: the +50 ms used to be a bare magic number on top of an
        // unmeasured latency (capturedAtNanos was the acquire time, not the
        // frame's own timestamp). It is the prediction horizon — where the
        // ball/players will be when the gesture lands — not a measured
        // delay. Named so it reads as a horizon, not a fudge factor; the
        // measured part now comes from Image.getTimestamp() (OverlayService
        // passes it, with an acquire-time fallback). The 100 ms cap is a
        // tactical bound: do not re-tune either number silently.
        val leadMs = ((now - capturedAtNanos).coerceAtLeast(0) / 1e6f + PREDICTION_LEAD_MS).coerceAtMost(100f)
        // Queue item 22 (leadMs loophole fix, Ed's decision 2026-11-21):
        // leadMs above stays the 100 ms-capped FRAME lead — it feeds
        // recordAssemble (so the PIPELINE_LATENCY sat rate keeps meaning
        // "age + 50 exceeded 100") and predictedAtNanos (ExecutionPolicy
        // clamps its dt to [0, 100 ms]). requestedLeadMs is the lead the
        // projector may actually use: the same age + 50 ms, capped at
        // 200. PredictiveState enforces the per-entity ceiling — a
        // straight-moving entity may use up to 200 ms on a late frame,
        // a turning entity is capped at 100 ms (today's behaviour).
        // PREDICTION_LEAD_MS and the 100 ms cap are NOT re-tuned here.
        val requestedLeadMs = ((now - capturedAtNanos).coerceAtLeast(0) / 1e6f + PREDICTION_LEAD_MS)
            .coerceAtMost(com.assistant.runtime.PredictiveState.EXTENDED_CAP_MS)
        // Queue item 7 (F5): end-to-end latency measurement — record the
        // age at assemble time + whether the 100 ms cap bound the lead.
        // Measurement only; the cap and PREDICTION_LEAD_MS are unchanged.
        PipelineLatencyMonitor.recordAssemble(capturedAtNanos, now, leadMs)
        val width = observedFrame.captureWidth; val height = observedFrame.captureHeight
        // PS-D3 (batch 5): the layout check + predictor reset + layout
        // assign used to be three unsynchronised steps on shared singleton
        // state, while overlapping visionScope launches run assemble()
        // concurrently — one frame could project against a predictor
        // another frame had just cleared for a new layout (velocity lost
        // for a frame). All three steps now run under the predictor's own
        // monitor (project/retain/reset are @Synchronized on it), so the
        // check-reset-assign is atomic against every project() call.
        synchronized(predictor) {
            if (predictionLayout != width to height) {
                predictor.reset(); predictionLayout = width to height
            }
        }
        val ball = if (ballSeen) predictor.project(Int.MIN_VALUE, ballX, ballY, capturedAtNanos, requestedLeadMs, width, height)
            else com.assistant.runtime.PredictedPoint(ballX, ballY, 0f, 0f)
        val projected = observedFrame.entities.map { entity ->
            val point = predictor.project(entity.id, entity.x, entity.y, capturedAtNanos, requestedLeadMs, width, height)
            entity.copy(x = point.x, y = point.y, velocityXPerSecond = point.vxPerSecond, velocityYPerSecond = point.vyPerSecond)
        }
        predictor.retain(projected.map { it.id }.toSet() + if (ballSeen) setOf(Int.MIN_VALUE) else emptySet())
        // B8: the projection shift follows the PASSING receiver now
        // (passingTarget), not the crossing lane's receiver.
        val receiver = passingTarget?.receiverId
        val before = observedFrame.entities.firstOrNull { it.id == receiver }
        val after = projected.firstOrNull { it.id == receiver }
        val frame = observedFrame.copy(ballX = ball.x, ballY = ball.y, entities = projected,
            ballVelocityXPerSecond = ball.vxPerSecond, ballVelocityYPerSecond = ball.vyPerSecond,
            capturedAtNanos = capturedAtNanos, predictedAtNanos = capturedAtNanos + (leadMs * 1e6f).toLong(),
            passTargetX = passTargetX + if (before != null && after != null) after.x - before.x else 0f,
            passTargetY = passTargetY + if (before != null && after != null) after.y - before.y else 0f,
            passTargetId = receiver)
        // G-C (batch 4, D12): copy the match state the OCR already
        // parsed (MatchStateEngine — one volatile read, no new load).
        // G-F / G-O (batch 4): publish the marking assignments + the
        // overload plan (primary + secondary) computed from the
        // projected entities. The secondary marker is PUBLISHED ONLY —
        // no second-player control exists to drive it.
        val matchState = try { MatchStateEngine.current() } catch (_: Throwable) { MatchState() }
        val marking = try { MarkingEngine.assign(frame) } catch (_: Throwable) { emptyList() }
        val overload = try { OverloadMarkingEngine.plan(frame) } catch (_: Throwable) {
            OverloadPlan(null, null, null, 0f, 0f)
        }
        val markingWithOverload = if (overload.opponentId != null && overload.secondaryDefenderId != null) {
            val already = marking.any {
                it.opponentId == overload.opponentId && it.defenderId == overload.secondaryDefenderId
            }
            if (already) marking else marking + MarkingAssignment(
                defenderId = overload.secondaryDefenderId,
                opponentId = overload.opponentId,
                targetX = overload.targetX,
                targetY = overload.targetY,
                overloadSecondary = true
            )
        } else marking
        val enriched = frame.copy(
            matchMinute = matchState.minute,
            matchScoreFor = matchState.scoreFor,
            matchScoreAgainst = matchState.scoreAgainst,
            matchPhase = matchState.phase,
            matchStateKnown = matchState.known,
            markingAssignments = markingWithOverload,
            overloadOpponentId = overload.opponentId,
            overloadSecondaryId = overload.secondaryDefenderId,
            // Q68-80 (batch 5b): the overload-balance fields the overload
            // engines already compute, copied from the world state so the
            // live frame names which side the overload favours.
            centralControl = world.centralOverloadDetectionResult.centralControl,
            leftWingAdvantage = world.wingOverloadDetectionResult.leftWingAdvantage,
            rightWingAdvantage = world.wingOverloadDetectionResult.rightWingAdvantage
        )
        lastFrame = enriched
        return enriched
    }

    fun current(): RuntimeFrame? = lastFrame

    fun reset() {
        // PS-D3 (batch 5): same monitor as the layout check in assemble()
        // — the predictor reset and the layout clear must not interleave
        // with another frame's check-reset-assign.
        synchronized(predictor) {
            predictor.reset()
            predictionLayout = null
        }
        frameCounter.set(0L)
        lastFrame = null
    }

    fun frameRuntimeSnapshot(): Map<String, Any> {
        val f = lastFrame ?: return mapOf("frames" to frameCounter.get(), "state" to "cold")
        return mapOf(
            "frames" to frameCounter.get(),
            "frameId" to f.frameId,
            "hasBall" to f.hasBall,
            "players" to f.playerCount,
            "opponents" to f.opponentCount,
            "viableLanes" to f.viableLaneCount,
            "zones" to "L${f.zones.leftOurs}v${f.zones.leftTheirs} M${f.zones.midOurs}v${f.zones.midTheirs} R${f.zones.rightOurs}v${f.zones.rightTheirs}",
            "passTargetX" to f.passTargetX,
            "passTargetY" to f.passTargetY,
            "goalDetected" to f.goalDetected,
            "goalConfidence" to f.goalConfidence,
            "confidence" to f.confidence,
            "trusted" to f.trusted,
            // L10/D8/L13/L14 (batch 1): the learning readout, cross type,
            // stance and home, so a live frame state names them.
            "crossType" to f.crossType.name,
            "opponentAggression" to f.opponentAggression,
            "offsideRisk" to f.offsideRisk,
            "counterattack" to f.counterattackDetected,
            "defensiveLineX" to f.defensiveLineX,
            "stanceTight" to f.stanceTight,
            "dragOutHeld" to f.dragOutHeld,
            "dragOutGapX" to f.dragOutGapX,
            "homeFound" to f.homeFound,
            // Midfield (queue item 5, batch midfield): the measured line
            // + its compactness, and the stance-derived fallback X.
            "midfieldLineX" to f.midfieldLineX,
            "midfieldFound" to f.midfieldFound,
            "midfieldCompactness" to f.midfieldCompactness,
            "stanceMidfieldX" to f.stanceMidfieldX,
            // G-C/G-F/G-O (batch 4): match state + marking/overload plan.
            "matchMinute" to f.matchMinute,
            "matchScore" to "${f.matchScoreFor}-${f.matchScoreAgainst}",
            "matchPhase" to f.matchPhase.name,
            "markingAssignments" to f.markingAssignments.size,
            "overloadOpponent" to (f.overloadOpponentId ?: -1),
            "overloadSecondary" to (f.overloadSecondaryId ?: -1),
            // Q68-80 (batch 5b): overload balance — which side the
            // overload favours (0 = no measured advantage).
            "centralControl" to f.centralControl,
            "leftWingAdvantage" to f.leftWingAdvantage,
            "rightWingAdvantage" to f.rightWingAdvantage
        )
    }
}

/* ========
FrameDropCompensationEngine
======== */
