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

internal const val ADHESION_COEFFICIENT = 1.25f       // Magnetic retention pull factor

internal const val DRIFT_FILTER_BETA = 0.90f          // Jitter filter strength

internal const val MIN_PULSE_INTERVAL_NS = 7_000_000L // ~142Hz micro-tick limit (7ms)

internal const val OMEGA_TURNING_THRESHOLD = 6.0f     // Joystick displacement speed threshold


object FrameAssembler {

    private val predictor = com.assistant.runtime.PredictiveState()
    private var predictionLayout: Pair<Float, Float>? = null
    private val frameCounter = AtomicLong(0L)

    @Volatile private var lastFrame: RuntimeFrame? = null

    fun assemble(opticalVariance: Float = 0f, capturedAtNanos: Long = System.nanoTime()): RuntimeFrame {
        val id = frameCounter.incrementAndGet()

        val world = Phase3WorldStateStore.current()
        val scene = SceneTracker.current().takeIf {
            System.currentTimeMillis() - it.timestamp in 0L..MAX_TELEMETRY_AGE_MS
        }
        val lanes = if (scene != null) world.crossingLaneAnalysis.lanes else emptyList()
        val laneCount = lanes.size
        val viable = lanes.count { it.viable }
        val bestLane = lanes.firstOrNull { it.viable }
        val passTargetX = bestLane?.targetX ?: 0f
        val passTargetY = bestLane?.targetY ?: 0f
        val bestConf = bestLane?.confidence ?: 0f

        val telemetry = TelemetryRepository.current().takeIf {
            System.currentTimeMillis() - it.timestamp in 0L..MAX_TELEMETRY_AGE_MS
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

        VisionTrust.tickAndLog()

        val goalDetected =
            (scene?.goalDetected ?: false) &&
                (scene?.goalConfidence ?: 0f) > 0f &&
                (scene?.goalRightX ?: 0f) > (scene?.goalLeftX ?: 0f) &&
                (scene?.goalBottomY ?: 0f) > (scene?.goalTopY ?: 0f)

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
                it.velocityX, it.velocityY, it.confidence, it.isUserTeam) },
            ownerId = world.ownership.takeIf { scene != null && it.hasOwner && it.confidence >= 0.2f }?.owner?.id,
            defenderId = world.defender.takeIf { scene != null && it.found }?.defender?.id,
            attackDirection = AttackAxisModel.directionIfConfident(),
            captureWidth = com.assistant.vision.CameraProfile.captureWidth.toFloat(),
            captureHeight = com.assistant.vision.CameraProfile.captureHeight.toFloat()
        )
        val now = System.nanoTime()
        val leadMs = ((now - capturedAtNanos).coerceAtLeast(0) / 1e6f + 50f).coerceAtMost(100f)
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
        val receiver = bestLane?.lane?.receiver?.id
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
            "trusted" to f.trusted
        )
    }
}

/* ========
FrameDropCompensationEngine
======== */
