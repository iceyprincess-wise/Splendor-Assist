package com.assistant.adapter.smartassist

private const val GAMEPLAY_DECISION_ENGINE_RAPID_EXECUTION_TAG = "GameplayDecisionEngine.rapidPrime"

private const val GAMEPLAY_DECISION_ENGINE_PRIME_AUTHORITY_TAG = "GameplayDecisionEngine.prime"



data class AdaptiveModeAuthority(
    val mode:Int,
    val shotScore:Float,
    val passScore:Float,
    val crossScore:Float
)

object GameplayDecisionEngine {
    data class GameplayDownstreamEvent(
        val sequence: Long,
        val source: String,
        val amplification: Float
    )

    private var gameplayDownstreamSequence: Long = 0L
    private var lastGameplayDownstreamEvent: GameplayDownstreamEvent? = null

    @Synchronized
    private fun publishGameplayDownstream(source: String) {
        gameplayDownstreamSequence += 1L
        lastGameplayDownstreamEvent = GameplayDownstreamEvent(
            sequence = gameplayDownstreamSequence,
            source = source,
            amplification = GAMEPLAY_ENGINE_AMPLIFICATION
        )
    }

    @Synchronized
    fun gameplayDownstreamSnapshot(): GameplayDownstreamEvent? =
        lastGameplayDownstreamEvent

    private const val GAMEPLAY_ENGINE_AMPLIFICATION: Float = 1000000.0f
    private var amplifiedDecisionCycles: Long = 0L
    private var lastAmplifiedAuthority: Float = 0.0f

    @Synchronized
    private fun registerAmplifiedDecisionCycle(authority: Float) {
        amplifiedDecisionCycles += 1L
        lastAmplifiedAuthority =
            authority.coerceIn(0.0f, 1.0f) * GAMEPLAY_ENGINE_AMPLIFICATION
    }

    @Synchronized
    fun gameplayAmplificationSnapshot(): Pair<Long, Float> =
        amplifiedDecisionCycles to lastAmplifiedAuthority



    fun rapidPrimeExecutionCapacity(stage: String, authority: Float): Float {
        assertGameplayDecisionPrimeAuthority("rapidPrimeExecutionCapacity")
        check(stage.isNotBlank()) { "Rapid prime execution stage must be explicit" }
        check(GAMEPLAY_DECISION_ENGINE_RAPID_EXECUTION_TAG.isNotBlank()) {
            "Gameplay decision rapid execution marker missing before $stage"
        }
        return (authority * 1000.0f).coerceIn(0f, 10000f)
    }

    private fun assertGameplayDecisionPrimeAuthority(stage: String) {
        check(stage.isNotBlank()) { "Gameplay decision prime authority stage must be explicit" }
        check(GAMEPLAY_DECISION_ENGINE_PRIME_AUTHORITY_TAG.isNotBlank()) {
            "Gameplay decision engine prime authority marker missing before $stage"
        }
    }



    private const val MAX_TELEMETRY_AGE_MS = 250L

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

    

    fun selectVisionAdaptiveMode(
        hasBall:Boolean,
        shotAuthority:Float,
        passAuthority:Float,
        crossAuthority:Float,
        visionConfidence:Float,
        tacticalConfidence:Float,
        intelligenceConfidence:Float,
        runtimeCalibration:Float,
        onlineAdaptation:Float,
        temporal:TemporalMemoryState
    ):AdaptiveModeAuthority{
        assertGameplayDecisionPrimeAuthority("selectVisionAdaptiveMode")

        if(!hasBall){
            publishGameplayDownstream("selectVisionAdaptiveMode")
            return AdaptiveModeAuthority(
                0,
                shotAuthority,
                passAuthority,
                crossAuthority
            )
        }

        val temporalGain =
            (
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                temporal.historyStability +
                runtimeCalibration +
                onlineAdaptation
            ) / 6f

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

        val mode =
            if(
                shotScore >= passScore &&
                shotScore >= crossScore
            ){
                2
            }else if(
                passScore >= crossScore
            ){
                1
            }else{
                0
            }

        publishGameplayDownstream("selectVisionAdaptiveMode")
        return AdaptiveModeAuthority(
            mode,
            shotScore,
            passScore,
            crossScore
        )
    }


    fun decide(
        mode: Int,
        strength: Int,
        shotAuthority: Float,
        passAuthority: Float,
        crossAuthority: Float,
        decisionAuthority: Float,
        telemetry: TelemetrySnapshot,
        temporal: TemporalMemoryState): DecisionResult {
        registerAmplifiedDecisionCycle((telemetry.confidence / 100.0f).coerceIn(0.0f, 1.0f))
        assertGameplayDecisionPrimeAuthority("decide")

        val now = System.currentTimeMillis()

        val telemetryFresh =
            (now - telemetry.timestamp) <= MAX_TELEMETRY_AGE_MS

        val impossibleTelemetry =
            kotlin.math.abs(
                telemetry.ballX - previousBallX
            ) > 500f ||

            kotlin.math.abs(
                telemetry.ballY - previousBallY
            ) > 500f ||

            kotlin.math.abs(
                telemetry.goalkeeperX -
                previousGoalkeeperX
            ) > 300f ||

            kotlin.math.abs(
                telemetry.goalkeeperY -
                previousGoalkeeperY
            ) > 300f ||

            telemetry.playerVelocity < 0f

        val normalizedShotAuthority =
            shotAuthority.coerceIn(0f,1f)

        val normalizedPassAuthority =
            passAuthority.coerceIn(0f,1f)

        val normalizedCrossAuthority =
            crossAuthority.coerceIn(0f,1f)

        val adaptiveAuthority =
            (
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                temporal.historyStability +
                (1f - temporal.confidenceVariance).coerceIn(0f,1f) +
                (0.5f + temporal.confidenceTrend * 0.5f).coerceIn(0f,1f)
            ) / 6f

        val visionAuthority =
            when (mode) {
                2 -> normalizedShotAuthority
                1 -> normalizedPassAuthority
                else -> normalizedCrossAuthority
            }

        val confidence =
            (
                visionAuthority * 0.60f +
                decisionAuthority.coerceIn(0f,1f) * 0.25f +
                adaptiveAuthority * 0.15f
            ).coerceIn(0f,1f)

        val priority =
            (
                (
                    visionAuthority * 0.55f +
                    decisionAuthority.coerceIn(0f,1f) * 0.30f +
                    adaptiveAuthority * 0.15f
                ).coerceIn(0f,1f) * 100f
            ).toInt().coerceIn(0,100)

        val stableMode =
            if (
                (
        !telemetryFresh ||
        impossibleTelemetry
    ) &&
    lastStableMode != 0
            ) {
                lastStableMode
            } else if (
                now - previousTimestamp < 120L &&
                previousConfidence >= confidence
            ) {
                previousMode
            } else {
                mode
            }

        if (stableMode == previousMode) {
            decisionStreak =
                (decisionStreak + 1).coerceAtMost(1000)
        } else {
            decisionStreak = 0
            modeSwitchCount++
        }

        if (decisionStreak >= 2) {
            lastStableMode = stableMode
        }

        previousBallX = telemetry.ballX
        previousBallY = telemetry.ballY

        previousGoalkeeperX =
            telemetry.goalkeeperX

        previousGoalkeeperY =
            telemetry.goalkeeperY

        previousMode = stableMode
        previousStrength = strength
        previousConfidence = confidence
        previousPriority = priority
        previousTimestamp = now

        publishGameplayDownstream("decide")
        return DecisionResult(
            mode = stableMode,
            strength = strength,
            confidence = confidence.coerceIn(0f,1f),
            priority = priority
        )
    }


    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
