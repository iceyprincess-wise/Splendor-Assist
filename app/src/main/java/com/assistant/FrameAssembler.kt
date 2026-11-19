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
                it.velocityXPerSecond, it.velocityYPerSecond) },
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
            // L14: the tracked defender's home on the tight line.
            homeX = world.defenderHome.x,
            homeY = world.defenderHome.y,
            homeFound = world.defenderHome.found,
            // B5: the raw user line (VisionCore → world store).
            userLineX = world.userLine.averageX,
            userLineSpread = (world.userLine.maxX - world.userLine.minX).coerceAtLeast(0f),
            userLineFound = world.userLine.found,
            // B4: the low-block movement list. Active only while QD-3
            // is resolved — the movements are published regardless, but
            // no consumer may apply them until the axis rework lands.
            lowBlockMovements = world.lowBlockMovements,
            lowBlockActive = LowBlockContainmentEngine.QD3_AXIS_RESOLVED && world.lowBlockMovements.isNotEmpty(),
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
        val width = observedFrame.captureWidth; val height = observedFrame.captureHeight
        if (predictionLayout != width to height) {
            predictor.reset(); predictionLayout = width to height
        }
        val ball = if (ballSeen) predictor.project(Int.MIN_VALUE, ballX, ballY, capturedAtNanos, leadMs, width, height)
            else com.assistant.runtime.PredictedPoint(ballX, ballY, 0f, 0f)
        val projected = observedFrame.entities.map { entity ->
            val point = predictor.project(entity.id, entity.x, entity.y, capturedAtNanos, leadMs, width, height)
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
        lastFrame = frame
        return frame
    }

    fun current(): RuntimeFrame? = lastFrame

    fun reset() {
        predictor.reset()
        predictionLayout = null
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
            "homeFound" to f.homeFound
        )
    }
}

/* ========
FrameDropCompensationEngine
======== */
