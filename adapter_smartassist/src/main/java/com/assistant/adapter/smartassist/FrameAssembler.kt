package com.assistant.adapter.smartassist

import com.assistant.runtime.RuntimeFrame
import java.util.concurrent.atomic.AtomicLong

/*
 * Reads the state stores ONCE per capture and produces one immutable frame.
 * Engines must never touch these stores directly after this exists — they
 * receive this frame instead. Guarded per-store so one missing store cannot
 * abort frame assembly.
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
        val bestConf = lanes.maxOfOrNull { it.confidence } ?: 0f

        val telemetry = try { TelemetryRepository.current() } catch (_: Throwable) { null }
        val ballX = telemetry?.ballX ?: 0f
        val ballY = telemetry?.ballY ?: 0f
        val hasBall = ballX != 0f || ballY != 0f

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val players = scene?.trackedPlayers.orEmpty()
        val opponents = players.count { !it.isUserTeam }

        val enabled = try { SmartAssistRepository.enabled() } catch (_: Throwable) { false }
        val panic = try { SmartAssistRepository.panicActive() } catch (_: Throwable) { false }

        val confidence = bestConf.coerceIn(0f, 1f)

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
            bestLaneConfidence = bestConf,
            defenderDensity = if (players.isNotEmpty())
                opponents.toFloat() / players.size else 0f,
            confidence = confidence,
            enabled = enabled,
            panic = panic
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
            "confidence" to f.confidence,
            "trusted" to f.trusted
        )
    }
}
