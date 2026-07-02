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


        val worldState =
            Phase3WorldStateStore.current()

        val pressureMap =
            worldState.pressure

        val scenePlayers =
            SceneTracker.current().trackedPlayers

        val defenderCount =
            scenePlayers.count {
                !it.isUserTeam
            }

        val pressureCellX =
            (
                (
                    telemetry.ballX.coerceIn(
                        0f,
                        1f.coerceAtLeast(distance)
                    ) /
                    1f.coerceAtLeast(distance)
                ) *
                pressureMap.columns
            ).toInt().coerceIn(
                0,
                pressureMap.columns-1
            )

        val pressureCellY =
            (
                (
                    telemetry.ballY.coerceIn(
                        0f,
                        1f.coerceAtLeast(distance)
                    ) /
                    1f.coerceAtLeast(distance)
                ) *
                pressureMap.rows
            ).toInt().coerceIn(
                0,
                pressureMap.rows-1
            )

        val visionPressure =
            pressureMap.pressure
                .getOrNull(pressureCellY)
                ?.getOrNull(pressureCellX)
                ?: 0f

        val defenderDensity =
            (defenderCount/11f)
                .coerceIn(0f,1f)

        val trajectory =
            BallTrajectoryPredictor.current()

        val scene =
            SceneTracker.current()

        val movementSpeed =
            scene.trackedBallSpeed
                .coerceAtLeast(0f)

        val decisionDistance =
            distance

        val goalFrameDetected =
            scene.goalDetected &&
            scene.goalConfidence > 0f

        val goalCenterX =
            (scene.goalLeftX + scene.goalRightX) * 0.5f

        val goalWidth =
            (scene.goalRightX - scene.goalLeftX)
                .coerceAtLeast(1f)

        val goalkeeperVisionBias =
            if(goalFrameDetected)
                (
                    kotlin.math.abs(
                        telemetry.goalkeeperX - goalCenterX
                    ) / goalWidth
                ).coerceIn(0f,2f)
            else
                0f


        val passingGraph =
            TrueTargetPassingEngine.currentPassingGraph()

        val bestPassingLane =
            passingGraph.lanes.firstOrNull()

        val passingGraphScore =
            (
                bestPassingLane?.score
                    ?: 0f
            ).coerceIn(0f,1f)

        val shootingLaneAnalysis =
            TrueTargetPassingEngine.currentShootingLaneAnalysis()

        val bestShootingLane =
            shootingLaneAnalysis.lanes
                .firstOrNull {
                    it.viable
                }

        val shootingLaneScore =
            (
                bestShootingLane?.confidence
                    ?: 0f
            ).coerceIn(0f,1f)

        val crossingLaneAnalysis =
            TrueTargetPassingEngine.currentCrossingLaneAnalysis()

        val bestCrossingLane =
            crossingLaneAnalysis.lanes
                .firstOrNull {
                    it.viable
                }

        val crossingLaneScore =
            (
                bestCrossingLane?.confidence
                    ?: 0f
            ).coerceIn(0f,1f)

        val shotScore =
            shootingLaneScore +
            goalkeeperVisionBias +
            scene.goalConfidence

        val passScore =
            passingGraphScore +
            trajectory.speed
                .coerceAtMost(12f)

        val crossScore =
            crossingLaneScore +
            scene.fieldConfidence

        val visionProximityConfidence =
            (
                visionPressure +
                scene.goalConfidence +
                scene.fieldConfidence +
                passingGraphScore +
                shootingLaneScore +
                crossingLaneScore +
                telemetry.confidence +
                (1f - (goalkeeperVisionBias * 0.5f).coerceIn(0f,1f))
            ).coerceIn(0f,8f) / 8f

        val mode =
            when {

                visionProximityConfidence < 0.35f ->
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
            (
                when (mode) {
                    2 -> shotScore
                    1 -> passScore
                    else -> crossScore
                }
            ) + visionProximityConfidence
        val adaptiveConfidence = worldState.runtimeConfidenceCalibrationResult.calibratedConfidence

        val temporal = worldState.temporalMemoryState

        val temporalGestureConfidence =
            (
                temporal.temporalConfidence * 0.30f +
                temporal.exponentialMovingAverage * 0.20f +
                temporal.rollingMean * 0.15f +
                (0.5f + temporal.confidenceTrend * 0.5f).coerceIn(0f,1f) * 0.15f +
                (1f - temporal.confidenceVariance).coerceIn(0f,1f) * 0.10f +
                temporal.historyStability * 0.05f +
                temporal.decayFactor * 0.05f
            ).coerceIn(0f,1f)




        val telemetryBoost =
            (
                movementSpeed * 12f +
                telemetry.confidence * 18f +
                (
                    visionPressure +
                    defenderDensity
                ).coerceIn(0f,1f).coerceAtMost(12f) +
                (((visionProximityConfidence * 12f) + (worldState.onlineParameterAdaptationResult.adaptationGain * 10f) + ((adaptiveConfidence + temporalGestureConfidence) * 8f)) + (decisionDistance * 0.01f))
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
                telemetry = telemetry,
                worldState.temporalMemoryState)

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
        val selectedPassingLane =
            passingGraph.lanes
                .firstOrNull {
                    !it.blocked
                }

        val receiverRankingResult =
            TrueTargetPassingEngine.currentReceiverRankingResult()

        val preferredReceiver =
            receiverRankingResult.receivers.firstOrNull()

        val runPredictionResult =
            TrueTargetPassingEngine.currentRunPredictionResult()

        val predictedRun =
            runPredictionResult.runs.firstOrNull{
                it.player.id==preferredReceiver?.player?.id
            }

        val overlapDetectionResult =
            TrueTargetPassingEngine.currentOverlapDetectionResult()

        val overlapRun =
            overlapDetectionResult.overlaps.firstOrNull{
                it.runner.id==preferredReceiver?.player?.id &&
                it.viable
            }

        val passTargetX =
            overlapRun?.supportX
                ?: predictedRun?.predictedX
                ?: preferredReceiver?.player?.x
                ?: selectedPassingLane
                    ?.receiver
                    ?.x
                ?: predictiveX

        val passTargetY =
            overlapRun?.supportY
                ?: predictedRun?.predictedY
                ?: preferredReceiver?.player?.y
                ?: selectedPassingLane
                    ?.receiver
                    ?.y
                ?: predictiveY

        val throughBallAnalysis =
            TrueTargetPassingEngine.currentThroughBallAnalysis()

        val selectedThroughLane =
            throughBallAnalysis.lanes
                .firstOrNull {
                    it.viable
                }

        val throughReceiverX =
            selectedThroughLane
                ?.lane
                ?.receiver
                ?.x
                ?: predictiveX

        val throughReceiverY =
            selectedThroughLane
                ?.lane
                ?.receiver
                ?.y
                ?: predictiveY

        val throughLead =
            selectedThroughLane
                ?.leadDistance
                ?: 0f

        val blockedLanePredictionAnalysis =
            TrueTargetPassingEngine.currentBlockedLanePredictionAnalysis()

        val predictedBlockedLane =
            blockedLanePredictionAnalysis.lanes
                .firstOrNull {
                    it.predictedBlocked
                }

        val effectivePassTargetX =
            if (
                predictedBlockedLane?.lane == selectedPassingLane
            ) predictiveX else passTargetX

        val effectivePassTargetY =
            if (
                predictedBlockedLane?.lane == selectedPassingLane
            ) predictiveY else passTargetY

        val defenderInterceptionPredictionAnalysis =
            TrueTargetPassingEngine.currentDefenderInterceptionPredictionAnalysis()

        val predictedInterception =
            defenderInterceptionPredictionAnalysis.lanes
                .firstOrNull {
                    it.predictedIntercept &&
                    it.lane == selectedPassingLane
                }

        val optimizedPassTargetX =
            predictedInterception
                ?.let {
                    effectivePassTargetX +
                    (
                        effectivePassTargetX -
                        it.predictedInterceptX
                    )*0.45f
                }
                ?: effectivePassTargetX

        val optimizedPassTargetY =
            predictedInterception
                ?.let {
                    effectivePassTargetY +
                    (
                        effectivePassTargetY -
                        it.predictedInterceptY
                    )*0.45f
                }
                ?: effectivePassTargetY

        val passAssist =
            TrueTargetPassingEngine.optimize(
                startX,
                startY,
                optimizedPassTargetX,
                optimizedPassTargetY,
                magneticFeet.touchRetention
            )

        ThroughPassSanitizer(latencyInputEngine).sanitizeAndInjectThroughPass(
            startX,
            startY,
            startX,
            startY,
            throughReceiverX,
            throughReceiverY - throughLead,
            predictiveX + 50f,
            predictiveY + 50f
        )

        AdaptiveLoftedThroughEngine(latencyInputEngine).executeOptimalLoftedThrough(
            startX,
            startY,
            throughReceiverX,
            throughReceiverY,
            1f,
            1f,
            1000f
        )

        val openSpaceDetectionResult =
            TrueTargetPassingEngine.currentOpenSpaceDetectionResult()

        val selectedOpenSpace =
            openSpaceDetectionResult.cells
                .firstOrNull {
                    it.viable
                }

        val selectedCrossLane =
            crossingLaneAnalysis.lanes
                .firstOrNull {
                    it.viable
                }

        val crossTargetX =
            selectedCrossLane
                ?.targetX
                ?: selectedOpenSpace
                    ?.centerX
                ?: passAssist.correctedX

        val crossTargetY =
            selectedCrossLane
                ?.targetY
                ?: selectedOpenSpace
                    ?.centerY
                ?: passAssist.correctedY

        val crossAssist =
            CrossPrecisionEngine.calculate(
                crossTargetX,
                crossTargetY,
                strength
            )

        val counterattackDetectionResult =
            TrueTargetPassingEngine.currentCounterattackDetectionResult()

        val fastBreakDetectionResult =
            TrueTargetPassingEngine.currentFastBreakDetectionResult()

        val offsideRiskEstimationResult =
            TrueTargetPassingEngine.currentOffsideRiskEstimationResult()

        val safestLane =
            offsideRiskEstimationResult.lanes
                .firstOrNull{
                    it.safe
                }

        val receiverEngagement =
            ReceiverEngagementEngine.evaluate(
                distance,
                magneticFeet.touchRetention +
                (counterattackDetectionResult.confidence*0.15f) +
                (fastBreakDetectionResult.confidence*0.10f) -
                ((safestLane?.risk ?: 0f)*0.10f)
            )

        val forwardRun =
            ForwardRunOpportunityEngine.evaluate(
                distance,
                strength
            )

        val selectedShootingLane =
            shootingLaneAnalysis.lanes
                .firstOrNull {
                    it.viable
                }

        val shootingPressure =
            (
                1f -
                (
                    selectedShootingLane
                        ?.confidence
                        ?: 0.75f
                )
            ).coerceIn(0f,1f)

        val shotDistance =
            selectedShootingLane
                ?.distance
                ?: distance

        val shotAnalysis =
            ShotOpportunityAnalysisEngine.analyze(
                shotDistance,
                shootingPressure
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
            ((movementSpeed * 4f) +
            (
                (
                    visionPressure +
                    defenderDensity
                ).coerceIn(0f,1f) * 2f
            ))

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



    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
