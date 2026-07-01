package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.FrameDropStabilizer
import com.assistant.adapter.smartassist.fps.MemoryStabilityOptimizer
import com.assistant.adapter.smartassist.fps.VsyncInputAnchor
import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionSource
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.HybridExecutionTerminal
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.atan2

class ActiveGestureController(
    private val service: AccessibilityService
) {

    private val memoryOptimizer = MemoryStabilityOptimizer(service)

    private val vsyncAnchor = VsyncInputAnchor { _ -> RuntimeLogger.log("VSync dispatch synchronized","SMART_ASSIST") }

    private val latencyInputEngine = LatencyDefeatingInputEngine(service)
    private val antiCutbackEngine = AntiCutbackSubEngine(latencyInputEngine)
    private val autoEvadeEngine = AutoEvadeEngine(latencyInputEngine)


    fun injectWinningVector(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ) {

        if (!SmartAssistRepository.enabled()) {
            RuntimeLogger.log("SmartAssist disabled, ignoring vector", "SMART_ASSIST")
            return
        }

        antiCutbackEngine.blockCutbackPassingLanes(endX, endY, startX, startY, 825f, 360f, 250f, 550f)
        autoEvadeEngine.monitorAttackingSpace(startX, startY, 250f, 550f, endX, endY, 0f, 0f)
        val scoreAim = CriticalAttackingVectorEngine.computeAbsoluteScoringVector(startX, startY, 1500f, 360f, 1620f, 280f, 1620f, 440f)
        OmnipotentDashPressureMatrix.computeHighAuthorityDefensiveVector(endX, endY, startX, startY, startX, startY + 50f, endX, endY, true)
        HybridOmnipotentMatrixEngine.computeGodspeedInterceptVector(startX, startY, endX, endY, 0f, 0f, endX, endY, 0f, 0f, false)
        val truePass = CriticalAttackingVectorEngine.computeTrueTargetPass(1300f, 550f, endX, endY, 0f, 0f, false)
        val deltaX = endX - startX
        val deltaY = endY - startY
        val distance = kotlin.math.hypot(deltaX, deltaY)

        val angle = Math.toDegrees(
            atan2(
                deltaY.toDouble(),
                deltaX.toDouble()
            )
        ).toFloat()

        val telemetry =
            TelemetryRepository.current()

        val hasBall =
            telemetry.ballX != 0f ||
            telemetry.ballY != 0f

        val pressureScore =
            (1000f /
            telemetry.opponentDistance.coerceAtLeast(1f))
                .coerceAtMost(10f)

        val movementScore =
            telemetry.playerVelocity
                .coerceAtLeast(0f)

        val goalkeeperOffset =
            kotlin.math.abs(
                telemetry.goalkeeperX -
                telemetry.ballX
            ) / 120f

        val goalkeeperBias =
            (
                telemetry.ballX -
                telemetry.goalkeeperX
            ) / 180f

        val predictedBallTravel =
            kotlin.math.hypot(
                telemetry.ballVelocityX,
                telemetry.ballVelocityY
            ).coerceAtMost(12f)

        val shotScore =
            goalkeeperOffset +
            movementScore +
            pressureScore +
            telemetry.confidence +
            (predictedBallTravel * 0.60f) +
            goalkeeperBias.coerceIn(-2f,2f)

        val passScore =
            movementScore +
            (1f - pressureScore.coerceAtMost(1f)) +
            telemetry.confidence +
            (predictedBallTravel * 0.35f) -
            (goalkeeperBias * 0.30f)

        val crossScore =
            (distance / 350f) +
            (movementScore * 0.50f) +
            (predictedBallTravel * 0.50f) +
            kotlin.math.abs(goalkeeperBias * 0.20f)

        val mode =
            when {

                distance < 80f ->
                    0

                hasBall &&
                shotScore >= passScore &&
                shotScore >= crossScore ->
                    2

                passScore >= crossScore ->
                    1

                else ->
                    0
            }

        val baseStrength =
            when (mode) {
                1 -> SmartAssistRepository.configuration().passThreshold
                2 -> SmartAssistRepository.configuration().shotThreshold
                else -> SmartAssistRepository.configuration().crossThreshold
            }

        val decisionScore =
            when (mode) {
                2 -> shotScore
                1 -> passScore
                else -> crossScore
            }

        val telemetryBoost =
            (
                telemetry.playerVelocity * 12f +
                telemetry.confidence * 18f +
                (
                    1000f /
                    telemetry.opponentDistance.coerceAtLeast(1f)
                ).coerceAtMost(12f)
            ).toInt()

        val strength =
            (
                baseStrength +
                decisionScore.toInt() +
                telemetryBoost
            ).coerceIn(0,100)


        val decision =
            GameplayDecisionEngine.decide(
                mode = mode,
                strength = strength,
                shotScore = shotScore,
                passScore = passScore,
                crossScore = crossScore,
                telemetry = telemetry
            )

        val compensation =
            HybridResponseCompensationEngine.compensate(
                startX,
                startY,
                endX,
                endY,
                duration,
                strength
            )

        val predictiveX = compensation.endX
        val predictiveY = compensation.endY

        val speedComp =
            SpeedCompensationEngine.compensate(
                distance,
                angle,
                strength
            )

        val shieldActive =
            ShieldAssistEngine.shouldEngageShield(
                speedComp.executionBoost,
                distance
            )

        val shieldAuthority =
            if(shieldActive) 20f else 0f

        val touchRecovery =
            TouchRecoveryEngine.recover(
                (distance.toInt().coerceIn(0,100)),
                strength
            )

        val magneticFeet =
            MagneticFeetEngine.stabilize(
                (distance.toInt().coerceIn(0,100)),
                strength
            )
        val passAssist =
            TrueTargetPassingEngine.optimize(
                startX,
                startY,
                predictiveX,
                predictiveY,
                magneticFeet.touchRetention
            )

        ThroughPassSanitizer(latencyInputEngine).sanitizeAndInjectThroughPass(
            startX,
            startY,
            startX,
            startY,
            predictiveX,
            predictiveY,
            predictiveX + 50f,
            predictiveY + 50f
        )

        AdaptiveLoftedThroughEngine(latencyInputEngine).executeOptimalLoftedThrough(
            startX,
            startY,
            predictiveX,
            predictiveY,
            1f,
            1f,
            1000f
        )

        val crossAssist =
            CrossPrecisionEngine.calculate(
                passAssist.correctedX,
                passAssist.correctedY,
                strength
            )

        val receiverEngagement =
            ReceiverEngagementEngine.evaluate(
                distance,
                magneticFeet.touchRetention
            )

        val forwardRun =
            ForwardRunOpportunityEngine.evaluate(
                distance,
                strength
            )

        val shotAnalysis =
            ShotOpportunityAnalysisEngine.analyze(
                distance,
                0.25f
            )

        val defenseAuthority =
            DefenseAuthorityEngine.evaluate(
                distance,
                strength,
                touchRecovery.recoveryBoost,
                magneticFeet.touchRetention
            )

        val lowBlockAuthority = (defenseAuthority.containment * 10.0f)

        val wingBlockAuthority = (defenseAuthority.interception * 10.0f)

        val boostedDuration =
            FrameDropCompensationEngine.compensate(
                (
                    duration /
                    speedComp.executionBoost
                ).toLong().coerceAtLeast(15L),
                strength
            )


        val inputDiagnostics =
            InputAccumulationDiagnosticsEngine.analyze(
                boostedDuration
            )

        val telemetryAuthority =
            ((telemetry.playerVelocity * 4f) +
            ((1000f / telemetry.opponentDistance.coerceAtLeast(1f)) * 2f))

        val arbitration =
            AuthorityArbitrationEngine.arbitrate(
                mode = mode,
                passX = truePass.x,
                passY = truePass.y,
                crossX = crossAssist.crossX,
                crossY = crossAssist.crossY,
                predictiveX = scoreAim.x,
                predictiveY = scoreAim.y,
                receiver = receiverEngagement.engagementBoost,
                forward = forwardRun.runBoost,
                recovery = touchRecovery.recoveryBoost,
                shot = shotAnalysis.openSideScore,
                stability =
                    inputDiagnostics.stabilityScore +
                    (defenseAuthority.pressure * 8.0f) +
                    lowBlockAuthority +
                    wingBlockAuthority +
                    shieldAuthority +
                    telemetryAuthority
            )

        val finalX = arbitration.finalX
        val finalY = arbitration.finalY

        SmartAssistMetrics.submitRequest()

        val request =
            ExecutionRequest(
                source = ExecutionSource.SMART_ASSIST,
                phase = decision.mode,
                startX = startX,
                startY = startY,
                endX = finalX,
                endY = finalY,
                duration = boostedDuration
            )

        HybridExecutionTerminal.route(request)

        SmartAssistAccessibilityEngine.globalInstance
            ?.executeDirectRequest(request)

        RuntimeLogger.log(
            "SmartAssist submitted mode=$mode distance=${distance.toInt()} strength=$strength panic=${SmartAssistRepository.panicActive()}",
            "SMART_ASSIST"
        )
    }

    fun emergencyClearanceVector(
        startX: Float,
        targetY: Float
    ): Pair<Float, Float> {

        return Pair(
            startX,
            targetY
        )
    }

    fun emergencyClearanceDuration(): Long {
        return 140L
    }

}
