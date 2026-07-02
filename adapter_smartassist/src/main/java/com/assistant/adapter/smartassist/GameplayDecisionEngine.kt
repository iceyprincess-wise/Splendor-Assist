package com.assistant.adapter.smartassist

object GameplayDecisionEngine {


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

    fun decide(
        mode: Int,
        strength: Int,
        shotAuthority: Float,
        passAuthority: Float,
        crossAuthority: Float,
        decisionAuthority: Float,
        telemetry: TelemetrySnapshot,
        temporal: TemporalMemoryState): DecisionResult {

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
