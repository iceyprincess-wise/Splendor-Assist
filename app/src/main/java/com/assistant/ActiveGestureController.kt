package com.assistant


import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.ExecutionSource
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.HybridExecutionTerminal
import android.accessibilityservice.AccessibilityService
import kotlin.math.atan2

data class ControllerEntryDiagnostics(
    val entryCalls: Long,
    val blockedCalls: Long,
    val lastReason: String,
    val lastStartX: Float,
    val lastStartY: Float,
    val lastEndX: Float,
    val lastEndY: Float,
    val lastDuration: Long,
    val lastUpdatedMs: Long
)

object ActiveGestureControllerDiagnostics {
    private var entryCalls: Long = 0L
    private var blockedCalls: Long = 0L
    private var lastReason: String = "not entered yet"
    private var lastStartX: Float = 0f
    private var lastStartY: Float = 0f
    private var lastEndX: Float = 0f
    private var lastEndY: Float = 0f
    private var lastDuration: Long = 0L
    private var lastUpdatedMs: Long = 0L

    @Synchronized
    fun reset() {
        entryCalls = 0L
        blockedCalls = 0L
        lastReason = "not entered yet"
        lastStartX = 0f
        lastStartY = 0f
        lastEndX = 0f
        lastEndY = 0f
        lastDuration = 0L
        lastUpdatedMs = 0L
    }

    @Synchronized
    fun snapshot(): ControllerEntryDiagnostics =
        ControllerEntryDiagnostics(
            entryCalls,
            blockedCalls,
            lastReason,
            lastStartX,
            lastStartY,
            lastEndX,
            lastEndY,
            lastDuration,
            lastUpdatedMs
        )

    @Synchronized
    fun entered(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ) {
        entryCalls += 1L
        lastReason = "injectWinningVector entered"
        lastStartX = startX
        lastStartY = startY
        lastEndX = endX
        lastEndY = endY
        lastDuration = duration
        lastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun blocked(reason: String) {
        blockedCalls += 1L
        lastReason = reason
        lastUpdatedMs = System.currentTimeMillis()
    }
}

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
        ActiveGestureControllerDiagnostics.entered(startX, startY, endX, endY, duration)

        if (!SmartAssistRepository.enabled()) {
            ActiveGestureControllerDiagnostics.blocked("SmartAssist disabled before controller execution")
            RuntimeLogger.log("SmartAssist disabled, ignoring vector", "SMART_ASSIST")
            return
        }

        if (!VisionTrust.isGameForeground()) {
            ActiveGestureControllerDiagnostics.blocked(
                "game foreground false before controller execution"
            )
            RuntimeLogger.log(
                "Game foreground false, ignoring legacy vector",
                "SMART_ASSIST"
            )
            return
        }

        memoryOptimizer.initialize()

        val earlyScene = SceneTracker.current()
        val nearestOppEarly = earlyScene.trackedPlayers
            .filter { !it.isUserTeam }
            .minByOrNull { p -> (p.x - startX) * (p.x - startX) + (p.y - startY) * (p.y - startY) }
        val earlyTraj = BallTrajectoryPredictor.current()

        val dynamicGoalLeftX = if (earlyScene.goalDetected) earlyScene.goalLeftX else 1620f
        val dynamicGoalLeftY = if (earlyScene.goalDetected) earlyScene.goalTopY else 280f
        val dynamicGoalRightX = if (earlyScene.goalDetected) earlyScene.goalRightX else 1620f
        val dynamicGoalRightY = if (earlyScene.goalDetected) earlyScene.goalBottomY else 440f
        val dynamicGkX = TelemetryRepository.current().goalkeeperX.let { if (it > 0f) it else 1500f }
        val dynamicGkY = TelemetryRepository.current().goalkeeperY.let { if (it > 0f) it else 360f }

        val antiCutbackGain = if (antiCutbackEngine.blockCutbackPassingLanes(
            nearestOppEarly?.x ?: endX, nearestOppEarly?.y ?: endY,
            startX, startY, dynamicGkX, dynamicGkY, startX, startY)) 1.0f else 0.0f
        val evadeActive = if (autoEvadeEngine.monitorAttackingSpace(
            startX, startY, startX, startY,
            nearestOppEarly?.x ?: endX, nearestOppEarly?.y ?: endY,
            earlyTraj.velocityX, earlyTraj.velocityY)) 1.0f else 0.0f
        val scoreAim = CriticalAttackingVectorEngine.computeAbsoluteScoringVector(startX, startY, dynamicGkX, dynamicGkY, dynamicGoalLeftX, dynamicGoalLeftY, dynamicGoalRightX, dynamicGoalRightY)
        val defensiveVectorPacked = OmnipotentDashPressureMatrix.computeHighAuthorityDefensiveVector(endX, endY, startX, startY, startX, startY + 50f, endX, endY, true)
        val godspeedVectorPacked = HybridOmnipotentMatrixEngine.computeGodspeedInterceptVector(startX, startY, endX, endY, 0f, 0f, endX, endY, 0f, 0f, false)
        val truePass = CriticalAttackingVectorEngine.computeTrueTargetPass(startX, startY, endX, endY, earlyTraj.velocityX, earlyTraj.velocityY, false)
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

        val hasBall = run {
            val own = Phase3WorldStateStore.current().ownership
            own.hasOwner &&
                SceneTracker.current().trackedPlayers
                    .getOrNull(own.ownerIndex)?.isUserTeam == true
        }


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

        val captureWidthSafe =
            com.assistant.vision.CameraProfile.captureWidthOrFallback().coerceAtLeast(1f)
        val captureHeightSafe =
            com.assistant.vision.CameraProfile.captureHeightOrFallback().coerceAtLeast(1f)
        val pressureColumnsSafe =
            pressureMap.columns.coerceAtLeast(1)
        val pressureRowsSafe =
            pressureMap.rows.coerceAtLeast(1)
        val pressureCellX =
            (
                (telemetry.ballX.coerceIn(0f, captureWidthSafe) / captureWidthSafe) *
                pressureColumnsSafe
            ).toInt().coerceIn(0, pressureColumnsSafe - 1)
        val pressureCellY =
            (
                (telemetry.ballY.coerceIn(0f, captureHeightSafe) / captureHeightSafe) *
                pressureRowsSafe
            ).toInt().coerceIn(0, pressureRowsSafe - 1)
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

        val shotAuthority =
            (
                shootingLaneScore +
                worldState.tacticalIntelligenceResult.confidence +
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence
            ).coerceIn(0f,1f)

        val passAuthority =
            (
                passingGraphScore +
                trajectory.speed.coerceIn(0f,1f) +
                worldState.buildUpRecognitionResult.confidence +
                worldState.possessionStyleRecognitionResult.confidence
            ).coerceIn(0f,1f)

        val crossAuthority =
            (
                crossingLaneScore +
                worldState.wingOverloadDetectionResult.confidence +
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence
            ).coerceIn(0f,1f)

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

        val decisionAuthority =
            (
                shotAuthority +
                passAuthority +
                crossAuthority +
                worldState.tacticalAnalyticsResult.confidence +
                worldState.temporalMemoryState.temporalConfidence +
                (decisionDistance / 2500f).coerceIn(0f,1f)
            ) / 6f

        val adaptiveModeAuthority =
            GameplayDecisionEngine.selectVisionAdaptiveMode(
                hasBall = hasBall,
                shotAuthority = shotAuthority,
                passAuthority = passAuthority,
                crossAuthority = crossAuthority,
                visionConfidence = visionProximityConfidence,
                tacticalConfidence =
                    worldState.tacticalAnalyticsResult.confidence,
                intelligenceConfidence =
                    worldState.tacticalIntelligenceResult.confidence,
                runtimeCalibration =
                    worldState.runtimeConfidenceCalibrationResult.calibratedConfidence,
                onlineAdaptation =
                    worldState.onlineParameterAdaptationResult.adaptationGain,
                temporal =
                    worldState.temporalMemoryState
            )

        val mode = adaptiveModeAuthority.mode

        val adaptiveDecisionAuthority =
            (
                decisionAuthority +
                shotAuthority +
                passAuthority +
                crossAuthority +
                worldState.runtimeConfidenceCalibrationResult.calibratedConfidence +
                worldState.onlineParameterAdaptationResult.adaptationGain +
                worldState.temporalMemoryState.temporalConfidence +
                worldState.tacticalAnalyticsResult.confidence +
                visionProximityConfidence
            ) / 9f

        val strength =
            (
                adaptiveDecisionAuthority
                    .coerceIn(0f,1f) * 100f
            ).toInt().coerceIn(0,100)

        val decision =
            GameplayDecisionEngine.decide(
                mode = mode,
                strength = strength,
                shotAuthority = shotAuthority,
                passAuthority = passAuthority,
                crossAuthority = crossAuthority,
                decisionAuthority = decisionAuthority,
                telemetry = telemetry,
                temporal = worldState.temporalMemoryState
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

        val speedProtectionAuthority =
            (
                (speedComp.interceptionProtection * 0.20f) +
                (speedComp.pressureCompensation * 0.15f) +
                (speedComp.laneCompensation * 0.15f)
            ).coerceIn(0f, 8f)
        val speedContainmentOffset =
            speedComp.containmentAngle
                .coerceIn(-15f, 15f)

        val rawAngleDegrees = Math.toDegrees(
            atan2(
                 deltaY.toDouble(),
                 deltaX.toDouble()
            )
        ).toFloat()
        val movementAngleDegrees = if (rawAngleDegrees.isNaN() || rawAngleDegrees.isInfinite()) 0f else rawAngleDegrees
        val safeAngleRatio = if (angle.isNaN() || angle.isInfinite()) 0f else (kotlin.math.abs(angle) / 180f).coerceIn(0f, 1f)
        val safeConfidence = if (telemetry.confidence.isNaN() || telemetry.confidence.isInfinite()) 0.5f else telemetry.confidence.coerceIn(0f, 1f)
        val safeDistance = if (distance.isNaN() || distance.isInfinite()) 0f else distance.coerceAtLeast(0f)

        // SPLENDOR_AGILITY_AGC_TELEMETRY_V1 - live carrier/opponent telemetry.
        // Nearest opponent to the carrier (the earlier scan in this method).
        val agilityOpponent = nearestOppEarly
        val agilityOppX = (agilityOpponent?.x ?: telemetry.ballX).let { if (it.isFinite()) it else 0f }
        val agilityOppY = (agilityOpponent?.y ?: telemetry.ballY).let { if (it.isFinite()) it else 0f }
        val agilityPlayerX = telemetry.ballX.let { if (it.isFinite()) it else 0f }
        val agilityPlayerY = telemetry.ballY.let { if (it.isFinite()) it else 0f }

        val agilityBuffer = FloatArray(6)
        /* SPLENDOR_AGILITY_VELOCITY_UNITS_V1 - the native engine declares a
           0..15 velocity space, but speedComp.executionBoost is a 10..35 boost
           value. Feeding it raw pinned the engine's speed term at its 1.0
           ceiling (speed = playerVelocity*0.06666667 saturates at 15), so the
           carrier's speed carried almost no information. Normalise to the
           declared contract. */
        val safeExecutionBoost =
            if (speedComp.executionBoost.isFinite() && speedComp.executionBoost > 0f) {
                speedComp.executionBoost
            } else {
                1f
            }
        val agilityVelocityUnits =
            (((safeExecutionBoost - 10f) / 25f) * 15f).coerceIn(0f, 15f)
        NativeBridge.nativeAgilityPhysics(
            agilityVelocityUnits, safeDistance, movementAngleDegrees,
            safeConfidence, safeAngleRatio,
            agilityPlayerX, agilityPlayerY, agilityOppX, agilityOppY,
            agilityBuffer,
            100.0f, 220.0f, 500.0f, 1200.0f,
            15.0f, 4.0f, 6.0f, 3.0f, 2.0f,
            45.0f, 0.3f, 0.5f
        )
        val agility = AgilityResult(
            // SPLENDOR_AGILITY_VELOCITY_UNITS_V1 - align with the other caller
            // (AgilityContributor gates on < 0.1f). Two different thresholds on
            // the same native signal made the shield behave differently
            // depending on which path produced the frame.
            shieldActive = agilityBuffer[5] > 0.1f,
            stabilityBoost = agilityBuffer[0],
            controlRetentionBoost = agilityBuffer[1],
            turnAssist = agilityBuffer[2],
            shieldAngleDegrees = agilityBuffer[3],
            shieldDurationMs = agilityBuffer[4].toLong()
        )

        val agilityShieldRad =
            Math.toRadians(agility.shieldAngleDegrees.toDouble())
        val agilityShieldDirX =
            kotlin.math.cos(agilityShieldRad).toFloat().let { if (it.isFinite()) it else 0f }
        val agilityShieldDirY =
            kotlin.math.sin(agilityShieldRad).toFloat().let { if (it.isFinite()) it else 0f }
        // Normalized shield-duration multiplier (engine range 40..124 ms -> 1.00..1.60).
        val agilityDurationScale =
            (
                1.0f +
                (
                    (agility.shieldDurationMs.toFloat().coerceIn(40f, 124f) - 40f) / 84f
                ) * 0.6f
            ).coerceIn(1.0f, 1.6f)
        val shieldAuthority =
            agility.stabilityBoost

        val touchRecovery =
            TouchRecoveryEngine.recover(
                (distance.toInt().coerceIn(0,100)),
                strength
            )

        val magneticFeet =
            MagneticFeetEngine.stabilize(
                distance.toInt().coerceIn(0, 100),
                strength
            )

        /*
         * Magnetic Feet contributes bounded specialist signals. Normalizing
         * here prevents it from overpowering the other arbitration engines.
         */
        val magneticTouchAuthority =
            (magneticFeet.touchRetention / 10.0f)
                .coerceIn(0.0f, 1.0f)

        val magneticInterceptionAuthority =
            (magneticFeet.interceptionResistance / 10.0f)
                .coerceIn(0.0f, 1.0f)

        val magneticPossessionAuthority =
            (magneticFeet.possessionControl / 10.0f)
                .coerceIn(0.0f, 1.0f)

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

        // Direct unmonitored injection calls removed to prevent rogue autonomous touches

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
                (
                    magneticFeet.touchRetention +
                        (magneticPossessionAuthority * 1.25f) +
                        (counterattackDetectionResult.confidence * 0.15f) +
                        (fastBreakDetectionResult.confidence * 0.10f) -
                        ((safestLane?.risk ?: 0f) * 0.10f)
                ).coerceIn(0.0f, 10.0f)
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
                (
                    magneticFeet.touchRetention +
                        (magneticInterceptionAuthority * 1.50f)
                ).coerceIn(0.0f, 10.0f)
            )

        val lowBlockAuthority = (defenseAuthority.containment * 10.0f)

        val wingBlockAuthority = (defenseAuthority.interception * 10.0f)

        val safeDurationBoost =
            if (speedComp.executionBoost.isFinite() && speedComp.executionBoost > 0f) {
                speedComp.executionBoost
            } else {
                1f
            }
        val safeBoostedBaseDuration =
            if (safeDurationBoost > 1f) {
                (duration / safeDurationBoost).toLong().coerceAtLeast(15L)
            } else {
                duration.coerceAtLeast(15L)
            }
        val boostedDuration =
            FrameDropCompensationEngine.compensate(
                safeBoostedBaseDuration,
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

        // SPLENDOR_THROUGH_PASS_ARBITRATION_V1
        // Viable through-ball telemetry stays inside the unified
        // arbitration -> execution bus -> accessibility path.
        val passArbitrationX =
            if (mode == 1 && selectedThroughLane != null) throughReceiverX 
            else if (passAssist.correctedX.isFinite()) passAssist.correctedX 
            else truePass.x

        val passArbitrationY =
            if (mode == 1 && selectedThroughLane != null) throughReceiverY - throughLead 
            else if (passAssist.correctedY.isFinite()) passAssist.correctedY 
            else truePass.y

        val arbitration =
            AuthorityArbitrationEngine.arbitrate(
                mode = mode,
                passX = passArbitrationX + speedContainmentOffset,
                passY = passArbitrationY + speedProtectionAuthority,
                crossX = crossAssist.crossX + speedContainmentOffset,
                crossY = crossAssist.crossY + speedProtectionAuthority,
                predictiveX = scoreAim.x + speedContainmentOffset,
                predictiveY = scoreAim.y + speedProtectionAuthority,
                receiver = receiverEngagement.engagementBoost,
                forward = forwardRun.runBoost,
                recovery = touchRecovery.recoveryBoost,
                shot = shotAnalysis.openSideScore,
                stability =
                    inputDiagnostics.stabilityScore +
                    speedProtectionAuthority +
                    (magneticTouchAuthority * 0.75f) +
                    (magneticPossessionAuthority * 0.75f) +
                    (magneticInterceptionAuthority * 0.50f) +
                    (defenseAuthority.pressure * 8.0f) +
                    lowBlockAuthority +
                    wingBlockAuthority +
                    shieldAuthority +
                    (agility.controlRetentionBoost * 4.0f) +
                    (agility.turnAssist * 2.0f) +
                    telemetryAuthority +
                    (antiCutbackGain * 0.2f) +
                    (evadeActive * 0.2f) +
                    if (defensiveVectorPacked != 0L) 0.1f else 0.0f +
                    if (godspeedVectorPacked != 0L) 0.1f else 0.0f
            )

        val finalX = arbitration.finalX
        val finalY = arbitration.finalY

        // SPLENDOR_AGILITY_AGC_TELEMETRY_V1 - when the native engine reports an
        // active shield, lead the gesture target along the computed shield
        // vector. Magnitude is preserved, so the joystick deflection the engine
        // affects is unchanged; only its direction is corrected to shield the ball.
        val finalDeltaX = finalX - startX
        val finalDeltaY = finalY - startY
        val finalMagnitude =
            kotlin.math.hypot(finalDeltaX.toDouble(), finalDeltaY.toDouble()).toFloat()
        val shieldActiveForTarget =
            agility.shieldActive && agilityShieldDirX.isFinite() && agilityShieldDirY.isFinite()
        val routedEndX =
            if (shieldActiveForTarget && finalMagnitude > 0.001f) {
                startX + agilityShieldDirX * finalMagnitude
            } else finalX
        val routedEndY =
            if (shieldActiveForTarget && finalMagnitude > 0.001f) {
                startY + agilityShieldDirY * finalMagnitude
            } else finalY
        val routedDuration =
            (boostedDuration * agilityDurationScale).toLong().coerceAtLeast(15L)

        val safeRoutedEndXInitial = if (routedEndX.isFinite()) routedEndX else endX
        val safeRoutedEndYInitial = if (routedEndY.isFinite()) routedEndY else endY
        val isPanic = SmartAssistRepository.panicActive()
        val (panicEndX, panicEndY) = if (isPanic) emergencyClearanceVector(safeRoutedEndXInitial, safeRoutedEndYInitial) else Pair(safeRoutedEndXInitial, safeRoutedEndYInitial)
        val finalDuration = if (isPanic) emergencyClearanceDuration() else routedDuration

        val safeRoutedEndX = if (panicEndX.isFinite()) panicEndX else endX
        val safeRoutedEndY = if (panicEndY.isFinite()) panicEndY else endY
        val safeRoutedDuration = finalDuration.coerceIn(15L, 10000L)
        val request =
            ExecutionRequest(
                source = ExecutionSource.SMART_ASSIST,
                phase = decision.mode,
                startX = startX,
                startY = startY,
                endX = safeRoutedEndX,
                endY = safeRoutedEndY,
                duration = safeRoutedDuration
            )
        val accepted =
            HybridExecutionTerminal.route(request)

        RuntimeLogger.log(
            "SmartAssist queued mode=$mode distance=${distance.toInt()} strength=$strength accepted=$accepted panic=${SmartAssistRepository.panicActive()}",
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




