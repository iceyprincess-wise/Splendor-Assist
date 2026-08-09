package com.assistant.adapter.smartassist

import com.assistant.runtime.RuntimeFrame
import com.assistant.runtime.ZoneDistribution
import java.util.concurrent.atomic.AtomicLong

/*
 * Reads the state stores ONCE per capture and produces one immutable frame.
 * Engines must never touch these stores directly after this exists — they
 * receive this frame instead. Guarded per-store so one missing store cannot
 * abort frame assembly.
 *
 * REPAIRED (Task B): hasBall was derived from raw stored coordinates, so
 * once a ball had EVER been seen the flag stayed true forever. hasBall now
 * requires the sighting to be FRESH (VisionTrust age-decay window).
 *
 * REPAIRED (Task C round 1): frame confidence was crossing-lane-only,
 * zeroing the whole contributor stack in any phase without a viable
 * crossing lane. Confidence now follows fresh ball trust.
 *
 * REPAIRED (Task C round 2 - FIELD-LOG PROVEN): hasBall meant "ball is
 * VISIBLE", not "WE possess it". The 18:38 field session shows the result:
 * hasBall=true for essentially the whole match, so every contributor gated
 * on !hasBall - ThreatPriority, CrossClaim, KeeperBias, PanicSave,
 * BallPress, and with them tackling/interception behaviour - recorded
 * ZERO contributions all session while the attack-side engines ran 6574
 * cycles. The keeper wasn't dying; it was never allowed to speak because
 * "ball on screen" was read as "we have it".
 *
 * Fix: hasBall now follows the REAL possession verdict from
 * BallPossessionEngine (via Phase3WorldState) whenever that verdict has
 * usable confidence; the fresh-sighting rule remains as fallback only when
 * possession has no data yet (cold start). Admin-tunable floor:
 *   assist.possession.min_conf (default 0.20)
 */
object FrameAssembler {

    private val frameCounter = AtomicLong(0L)

    @Volatile private var lastFrame: RuntimeFrame? = null

    fun assemble(): RuntimeFrame {
        val id = frameCounter.incrementAndGet()

        val crossing = try {
            CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()
        } catch (_: Throwable) { null }
        val lanes = crossing?.result?.lanes.orEmpty()
        val laneCount = lanes.size
        val viable = lanes.count { it.viable }
        val bestLane = lanes.firstOrNull { it.viable }
        val passTargetX = bestLane?.targetX ?: 0f
        val passTargetY = bestLane?.targetY ?: 0f
        val bestConf = lanes.maxOfOrNull { it.confidence } ?: 0f

        val telemetry = try { TelemetryRepository.current() } catch (_: Throwable) { null }
        val ballX = telemetry?.ballX ?: 0f
        val ballY = telemetry?.ballY ?: 0f
        val ballTrustNow = VisionTrust.ballTrust()
        val ballSeen = ballTrustNow > 0f && (ballX != 0f || ballY != 0f)

        /*
         * ROOT-CAUSE FIX (round 2): possession, not visibility.
         * BallPossessionEngine already computes true ownership every vision
         * cycle; the frame just never consumed it.
         */
        val possession = try {
            Phase3WorldStateStore.current().possession
        } catch (_: Throwable) { null }
        val possessionMinConf = try {
            com.assistant.admin.AdminConfigStore.get("assist.possession.min_conf", 0.20f)
        } catch (_: Throwable) { 0.20f }
        val hasBall =
            if (possession != null && possession.confidence >= possessionMinConf) {
                ballSeen && possession.hasPossession
            } else {
                ballSeen // cold-start fallback: old fresh-sighting semantics
            }

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val players = scene?.trackedPlayers.orEmpty()
        val opponents = players.count { !it.isUserTeam }

        // Real per-zone counts from tracked player positions (landscape thirds).
        val pitchH = 720f
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
        val rawConfidence = maxOf(bestConf, ballTrustNow).coerceIn(0f, 1f)
        val confidence =
            if (VisionTrust.frameTrusted(players.size, opponents)) rawConfidence else 0f

        VisionTrust.tickAndLog()

        val goalDetected =
            (scene?.goalDetected ?: false) &&
                (scene?.goalConfidence ?: 0f) > 0f &&
                (scene?.goalRightX ?: 0f) > (scene?.goalLeftX ?: 0f) &&
                (scene?.goalBottomY ?: 0f) > (scene?.goalTopY ?: 0f)

        val frame = RuntimeFrame(
            frameId = id,
            timestampMs = System.currentTimeMillis(),
            hasBall = hasBall,
            ballX = ballX,
            ballY = ballY,
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
            goalkeeperY = scene?.goalkeeperY ?: 0f
        )
        lastFrame = frame
        return frame
    }

    fun current(): RuntimeFrame? = lastFrame

    fun reset() {
        frameCounter.set(0L)
        lastFrame = null
    }

    fun frameRuntimeSnapshot(): Map<String, Any> {
        val f = lastFrame ?: return mapOf("frames" to frameCounter.get(), "state" to "cold")
        return mapOf(
            "frames" to frameCounter.get(),
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
