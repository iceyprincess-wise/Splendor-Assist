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
 * REPAIRED (Task C - THE ROOT CAUSE OF "engines work, then go dead"):
 * frame confidence was derived ONLY from crossing-lane confidence
 * (bestConf of CrossingLaneAnalysisEngine). Crossing lanes exist mainly in
 * box/wing situations - so during those phases confidence > 0, the frame
 * was trusted, and every contributor fired (keeper saving "like a maniac").
 * The moment play moved to a phase with no viable crossing lane, bestConf
 * was 0 -> confidence 0 -> frame UNTRUSTED -> RuntimeDecisionLoop returned
 * before collecting ANY contribution. The entire 31-contributor stack -
 * tackling, interception, magnetic feet, agility, attackers - went dead
 * simultaneously, then "revived" when a crossing lane appeared again.
 * Nothing was broken inside those engines; their input feed was being
 * zeroed out by an unrelated signal.
 *
 * Fix: a frame is now as confident as its FRESH BALL SIGHTING (age-decayed
 * VisionTrust ball trust). Lane confidence can only RAISE it, never zero
 * it. VisionTrust.frameTrusted still gates everything (game foreground,
 * sane entity counts, latency, trust floor) - no trust is invented.
 *
 * EXTENDED (Task C item (d)): goal detector output rides in the frame so
 * SHOT/CROSS contributors never fabricate a target.
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
        // possession is only claimed on a FRESH sighting - stale stored
        // coordinates said "we have the ball" forever and starved every
        // !hasBall-gated contributor (keeper, intercept) of its turn
        val ballTrustNow = VisionTrust.ballTrust()
        val hasBall = ballTrustNow > 0f && (ballX != 0f || ballY != 0f)

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

        /*
         * ROOT-CAUSE FIX (see class doc): confidence follows the fresh ball
         * sighting, with lane data only able to raise it. frameTrusted still
         * hard-gates: foreground, entity sanity, latency, trust floor.
         */
        val rawConfidence = maxOf(bestConf, ballTrustNow).coerceIn(0f, 1f)
        val confidence =
            if (VisionTrust.frameTrusted(players.size, opponents)) rawConfidence else 0f

        VisionTrust.tickAndLog()

        // Task C: goal detector output, taken as-is. A goal is only
        // "detected" for contributors when the detector says so AND the
        // box is geometrically sane (right of left, bottom below top).
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
