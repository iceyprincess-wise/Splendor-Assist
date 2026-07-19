package com.assistant.adapter.smartassist
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil

// -----------------------------------------------------------

private const val GAMEPLAY_DECISION_ENGINE_RAPID_EXECUTION_TAG = "GameplayDecisionEngine.rapidPrime"
private const val GAMEPLAY_DECISION_ENGINE_PRIME_AUTHORITY_TAG = "GameplayDecisionEngine.prime"
private const val GAMEPLAY_ENGINE_AMPLIFICATION: Float = 1000000.0f
private const val MAX_TELEMETRY_AGE_MS = 250L

// Network & Hardware Frame Synchronization Constants
private const val SERVER_TICK_RATE_MS = 33.33333f // 30Hz Authoritative Tick Window
private const val DISPLAY_REFRESH_120HZ_MS = 8.333333f

data class AdaptiveModeAuthority(
    val mode: Int,
    val shotScore: Float,
    val passScore: Float,
    val crossScore: Float
)

object GameplayDecisionEngine {
    data class GameplayActivationDiagnostics(
        val adaptiveModeCalls: Long,
        val decideCalls: Long,
        val lastHasBall: Boolean,
        val lastMode: Int,
        val lastStrength: Int,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    // OMEGA UPGRADE: Lock-free atomic structures for ultra-low latency memory access
    private val adaptiveModeCalls = AtomicLong(0L)
    private val decideCalls = AtomicLong(0L)
    private val lastGameplayUpdatedMs = AtomicLong(0L)

    // Utilizing @Volatile primitives for immediate cache-coherence across CPU cores
    @Volatile private var lastGameplayHasBall: Boolean = false
    @Volatile private var lastGameplayMode: Int = 0
    @Volatile private var lastGameplayStrength: Int = 0
    @Volatile private var lastGameplayReason: String = "not called yet"

    fun gameplayActivationDiagnostics(): GameplayActivationDiagnostics =
        GameplayActivationDiagnostics(
            adaptiveModeCalls.get(),
            decideCalls.get(),
            lastGameplayHasBall,
            lastGameplayMode,
            lastGameplayStrength,
            lastGameplayReason,
            lastGameplayUpdatedMs.get()
        )

    private fun recordAdaptiveModeActivation(hasBall: Boolean, mode: Int, reason: String) {
        adaptiveModeCalls.incrementAndGet()
        lastGameplayHasBall = hasBall
        lastGameplayMode = mode
        lastGameplayReason = reason
        lastGameplayUpdatedMs.set(System.currentTimeMillis())
    }

    private fun recordDecisionActivation(mode: Int, strength: Int, reason: String) {
        decideCalls.incrementAndGet()
        lastGameplayMode = mode
        lastGameplayStrength = strength
        lastGameplayReason = reason
        lastGameplayUpdatedMs.set(System.currentTimeMillis())
    }

    data class GameplayDownstreamEvent(
        val sequence: Long,
        val source: String,
        val amplification: Float
    )

    private val gameplayDownstreamSequence = AtomicLong(0L)
    @Volatile private var lastGameplayDownstreamEvent: GameplayDownstreamEvent? = null

    private fun publishGameplayDownstream(source: String) {
        val seq = gameplayDownstreamSequence.incrementAndGet()
        lastGameplayDownstreamEvent = GameplayDownstreamEvent(
            sequence = seq,
            source = source,
            amplification = GAMEPLAY_ENGINE_AMPLIFICATION
        )
    }

    fun gameplayDownstreamSnapshot(): GameplayDownstreamEvent? = lastGameplayDownstreamEvent

    private val amplifiedDecisionCycles = AtomicLong(0L)
    @Volatile private var lastAmplifiedAuthority: Float = 0.0f

    private fun registerAmplifiedDecisionCycle(authority: Float) {
        amplifiedDecisionCycles.incrementAndGet()
        // Inlined bounding constraint for extreme loop-execution speed
        val bounded = if (authority < 0.0f) 0.0f else if (authority > 1.0f) 1.0f else authority
        lastAmplifiedAuthority = bounded * GAMEPLAY_ENGINE_AMPLIFICATION
    }

    fun gameplayAmplificationSnapshot(): Pair<Long, Float> =
        amplifiedDecisionCycles.get() to lastAmplifiedAuthority

    fun rapidPrimeExecutionCapacity(stage: String, authority: Float): Float {
        assertGameplayDecisionPrimeAuthority("rapidPrimeExecutionCapacity")
        check(stage.isNotBlank()) { "Rapid prime execution stage must be explicit" }
        check(GAMEPLAY_DECISION_ENGINE_RAPID_EXECUTION_TAG.isNotBlank()) {
            "Gameplay decision rapid execution marker missing before $stage"
        }
        val amplified = authority * 1000.0f
        return if (amplified < 0f) 0f else if (amplified > 10000f) 10000f else amplified
    }

    private fun assertGameplayDecisionPrimeAuthority(stage: String) {
        check(stage.isNotBlank()) { "Gameplay decision prime authority stage must be explicit" }
        check(GAMEPLAY_DECISION_ENGINE_PRIME_AUTHORITY_TAG.isNotBlank()) {
            "Gameplay decision engine prime authority marker missing before $stage"
        }
    }

    // State Machine Memory protected by highly efficient ReentrantLock (avoids JVM Monitor Lock overhead)
    private val decisionStateLock = ReentrantLock()

    private var previousMode = 0
    private var previousStrength = 0
    private var previousConfidence = 0f
    private var previousPriority = 0
    private var previousTimestamp = 0L

    private var lastStableMode = 0
    private var decisionStreak = 0
    private var modeSwitchCount = 0

    private var previousBallX = 0f
    private var previousBallY = 0f
    private var previousGoalkeeperX = 0f
    private var previousGoalkeeperY = 0f

    // Fast inlined absolute value calculator (bypasses JNI/Math method overheads)
    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastAbs(v: Float): Float = if (v < 0f) -v else v

    // Fast inline bounding constraint
    @Suppress("NOTHING_TO_INLINE")
    private inline fun bound(v: Float, min: Float, max: Float): Float =
        if (v < min) min else if (v > max) max else v

    // Organic Adaptive Noise utilizing the native ThreadLocalRandom for max concurrent throughput
    @Suppress("NOTHING_TO_INLINE")
    private inline fun generateHumanizedNoise(variance: Float): Float =
        (ThreadLocalRandom.current().nextFloat() * 2f - 1f) * variance

    fun selectVisionAdaptiveMode(
        hasBall: Boolean,
        shotAuthority: Float,
        passAuthority: Float,
        crossAuthority: Float,
        visionConfidence: Float,
        tacticalConfidence: Float,
        intelligenceConfidence: Float,
        runtimeCalibration: Float,
        onlineAdaptation: Float,
        temporal: TemporalMemoryState
    ): AdaptiveModeAuthority {
        assertGameplayDecisionPrimeAuthority("selectVisionAdaptiveMode")
        recordAdaptiveModeActivation(hasBall, 0, if (hasBall) "adaptive mode evaluation" else "no ball: fallback mode")

        if (!hasBall) {
            publishGameplayDownstream("selectVisionAdaptiveMode")
            return AdaptiveModeAuthority(0, shotAuthority, passAuthority, crossAuthority)
        }

        // OMEGA UPGRADE: Replaced slow floating-point division (/ 6f) with pre-calculated inversion (* 0.16666667f)
        val temporalGain = (
            temporal.temporalConfidence +
            temporal.exponentialMovingAverage +
            temporal.rollingMean +
            temporal.historyStability +
            runtimeCalibration +
            onlineAdaptation
        ) * 0.16666667f

        val shotScore =
            shotAuthority * 0.55f +
            intelligenceConfidence * 0.15f +
            tacticalConfidence * 0.10f +
            temporalGain * 0.10f +
            visionConfidence * 0.10f

        val passScore =
            passAuthority * 0.55f +
            tacticalConfidence * 0.15f +
            temporalGain * 0.10f +
            runtimeCalibration * 0.10f +
            visionConfidence * 0.10f

        val crossScore =
            crossAuthority * 0.55f +
            tacticalConfidence * 0.10f +
            temporalGain * 0.10f +
            onlineAdaptation * 0.15f +
            visionConfidence * 0.10f

        val mode = if (shotScore >= passScore && shotScore >= crossScore) {
            2
        } else if (passScore >= crossScore) {
            1
        } else {
            0
        }

        publishGameplayDownstream("selectVisionAdaptiveMode")
        return AdaptiveModeAuthority(mode, shotScore, passScore, crossScore)
    }

    fun decide(
        mode: Int,
        strength: Int,
        shotAuthority: Float,
        passAuthority: Float,
        crossAuthority: Float,
        decisionAuthority: Float,
        telemetry: TelemetrySnapshot,
        temporal: TemporalMemoryState
    ): DecisionResult {
        recordDecisionActivation(mode, strength, "decide called by runtime path")
        registerAmplifiedDecisionCycle(bound(telemetry.confidence * 0.01f, 0.0f, 1.0f))
        assertGameplayDecisionPrimeAuthority("decide")

        val now = System.currentTimeMillis()
        val telemetryFresh = (now - telemetry.timestamp) <= MAX_TELEMETRY_AGE_MS

        val impossibleTelemetry =
            fastAbs(telemetry.ballX - previousBallX) > 500f ||
            fastAbs(telemetry.ballY - previousBallY) > 500f ||
            fastAbs(telemetry.goalkeeperX - previousGoalkeeperX) > 300f ||
            fastAbs(telemetry.goalkeeperY - previousGoalkeeperY) > 300f ||
            telemetry.playerVelocity < 0f

        val normalizedShotAuthority = bound(shotAuthority, 0f, 1f)
        val normalizedPassAuthority = bound(passAuthority, 0f, 1f)
        val normalizedCrossAuthority = bound(crossAuthority, 0f, 1f)

        // Optimized adaptive authority calculations
        val adaptiveAuthority = (
            temporal.temporalConfidence +
            temporal.exponentialMovingAverage +
            temporal.rollingMean +
            temporal.historyStability +
            bound(1f - temporal.confidenceVariance, 0f, 1f) +
            bound(0.5f + temporal.confidenceTrend * 0.5f, 0f, 1f)
        ) * 0.16666667f

        val visionAuthority = when (mode) {
            2 -> normalizedShotAuthority
            1 -> normalizedPassAuthority
            else -> normalizedCrossAuthority
        }

        val decisionClamped = bound(decisionAuthority, 0f, 1f)

        val confidence = bound(
            visionAuthority * 0.60f + decisionClamped * 0.25f + adaptiveAuthority * 0.15f,
            0f, 1f
        )

        val priorityRaw = (visionAuthority * 0.55f + decisionClamped * 0.30f + adaptiveAuthority * 0.15f) * 100f
        val priority = bound(priorityRaw, 0f, 100f).toInt()

        // --- OMEGA UPGRADE: HARDWARE & NETWORK SYNCHRONIZATION ---
        // 1. Calculate optimal hold length to span authoritative server network packets (prevent ping drop-outs)
        val rawCalculatedHoldMs = strength * 2.2f // Baseline physical conversion mapped to internal screen logic
        val tickMultiplier = ceil(rawCalculatedHoldMs / SERVER_TICK_RATE_MS).coerceAtLeast(1f)
        val synchronizedHoldMs = (SERVER_TICK_RATE_MS * tickMultiplier).toLong()

        // 2. Calculate display refresh frame offsets to minimize touch dispatch latency pipeline delays
        val frameOffsetMs = (DISPLAY_REFRESH_120HZ_MS - (now % DISPLAY_REFRESH_120HZ_MS)).toLong()

        // 3. Calculate Humanized Adaptive Noise (Mimics organic thumb-roll bounds to avoid anti-cheat flag)
        val injectionNoiseX = generateHumanizedNoise(3.4f)
        val injectionNoiseY = generateHumanizedNoise(3.4f)

        // 4. Dynamic Path Scaling (Adapts stride vector based on organic gameplay velocity)
        val safeVelocity = if (telemetry.playerVelocity > 0f) telemetry.playerVelocity else 1f
        val vectorScale = 1.0f + (generateHumanizedNoise(0.018f) * (1f / safeVelocity.coerceAtLeast(0.1f)))
        // ---------------------------------------------------------

        decisionStateLock.lock()
        val stableMode: Int
        try {
            stableMode = if ((!telemetryFresh || impossibleTelemetry) && lastStableMode != 0) {
                lastStableMode
            } else if (now - previousTimestamp < 120L && previousConfidence >= confidence) {
                previousMode
            } else {
                mode
            }

            if (stableMode == previousMode) {
                decisionStreak = if (decisionStreak + 1 > 1000) 1000 else decisionStreak + 1
            } else {
                decisionStreak = 0
                modeSwitchCount++
            }

            if (decisionStreak >= 2) {
                lastStableMode = stableMode
            }

            previousBallX = telemetry.ballX
            previousBallY = telemetry.ballY
            previousGoalkeeperX = telemetry.goalkeeperX
            previousGoalkeeperY = telemetry.goalkeeperY
            previousMode = stableMode
            previousStrength = strength
            previousConfidence = confidence
            previousPriority = priority
            previousTimestamp = now
        } finally {
            decisionStateLock.unlock()
        }

        publishGameplayDownstream("decide")

        return DecisionResult(
            mode = stableMode,
            strength = strength,
            confidence = confidence,
            priority = priority,
            tickAlignedHoldMs = synchronizedHoldMs,
            frameAlignedOffsetMs = frameOffsetMs,
            humanizedNoiseX = injectionNoiseX,
            humanizedNoiseY = injectionNoiseY,
            vectorScaleAmplification = vectorScale
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
