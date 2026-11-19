package com.assistant


import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.ExecutionSource
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.runtime.ActionClass
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

    init {
        // L8 (batch 1): register the optimizer with the liveness reporter
        // at controller creation — the startup `optimizer=` line fires
        // here, not behind the gesture gate (Q-N7-4: initialize() is
        // gesture-gated, so the reporter must not be).
        OptimizerLivenessReporter.noteRegistered(memoryOptimizer)
    }

    /**
     * L8 (batch 1): the controller teardown path (Q68-41: terminate()
     * was never called — no teardown hook existed). Called from
     * SmartAssistAccessibilityEngine.onDestroy. Terminates the optimizer
     * and reports it; safe to call twice (terminate() is idempotent).
     */
    fun shutdown() {
        try { memoryOptimizer.terminate() } catch (_: Throwable) { }
        OptimizerLivenessReporter.noteTerminated()
    }

    private val vsyncAnchor = VsyncInputAnchor { _ -> RuntimeLogger.log("VSync dispatch synchronized","SMART_ASSIST") }

    // D13: the controller owns the only LatencyDefeatingInputEngine, so
    // it binds it into the through-pass sanitizer gateway at creation —
    // the gateway (called from RuntimeDecisionLoop.onFrame) then has a
    // real engine to sanitize Ed's through presses with. Bound here with
    // .also (not in an init block): the init blocks above run before
    // this property is initialized, so a bind there would see null.
    private val latencyInputEngine = LatencyDefeatingInputEngine(service).also {
        ThroughPassSanitizerGateway.bind(it)
    }
    private val antiCutbackEngine = AntiCutbackSubEngine(latencyInputEngine)
    private val autoEvadeEngine = AutoEvadeEngine()


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

        // GHOST-TOUCH (batch G): injectWinningVector is a joystick drag
        // (joystick centre -> ball/target) fired by the parked-lane
        // trigger while the loop itself decided nothing (best == null).
        // Firing it while the user is not touching a control is the app
        // moving the phone on its own. Gate it on user touch, like the
        // onFrame MOVE hold: the trigger only primes the parked evade
        // while Ed is holding a control (autopilot dribble/skill is
        // defined as while holding dash+pressure). Deliberately NOT the
        // bypass: bypass ON must not re-open autonomous movement.
        if (!UserTouchState.isTouching()) {
            ActiveGestureControllerDiagnostics.blocked(
                "no user touch before controller execution"
            )
            RuntimeLogger.log(
                "No user touch, ignoring legacy vector",
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
        // Keeper position: prefer the tracked keeper, then live telemetry, then
        // the real detected goal centre (the keeper stands on the goal line).
        // The previous hard-coded 1500f/360f was presented to the scoring
        // vector as if it were a measured keeper position.
        val liveTelemetry = TelemetryRepository.current()
        val dynamicGkX = when {
            earlyScene.goalkeeperVisible -> earlyScene.goalkeeperX
            liveTelemetry.goalkeeperX > 0f -> liveTelemetry.goalkeeperX
            earlyScene.goalDetected -> (earlyScene.goalLeftX + earlyScene.goalRightX) / 2f
            else -> 0f
        }
        val dynamicGkY = when {
            earlyScene.goalkeeperVisible -> earlyScene.goalkeeperY
            liveTelemetry.goalkeeperY > 0f -> liveTelemetry.goalkeeperY
            earlyScene.goalDetected -> (earlyScene.goalTopY + earlyScene.goalBottomY) / 2f
            else -> 0f
        }

        val antiCutbackGain = if (antiCutbackEngine.blockCutbackPassingLanes(
            nearestOppEarly?.x ?: endX, nearestOppEarly?.y ?: endY,
            startX, startY, dynamicGkX, dynamicGkY, startX, startY)) 1.0f else 0.0f
        val evadeActive = if (autoEvadeEngine.monitorAttackingSpace(
            startX, startY, startX, startY,
            nearestOppEarly?.x ?: endX, nearestOppEarly?.y ?: endY,
            nearestOppEarly?.velocityX ?: 0f, nearestOppEarly?.velocityY ?: 0f)) 1.0f else 0.0f
        val scoreAim = CriticalAttackingVectorEngine.computeAbsoluteScoringVector(startX, startY, dynamicGkX, dynamicGkY, dynamicGoalLeftX, dynamicGoalLeftY, dynamicGoalRightX, dynamicGoalRightY)
        val defensiveVectorPacked = OmnipotentDashPressureMatrix.computeHighAuthorityDefensiveVector(endX, endY, startX, startY, startX, startY + 50f, endX, endY, UserTouchState.isTouching())
        // No68-I-D36: the last argument above was a hardcoded `true`, so the
        // matrix's direct INTERCEPTION offer fired on the legacy path even
        // when the user was NOT holding pressure. Pass the real touch state
        // (the same signal DashPressureContributor uses).
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

        // The Pass/Shot/Cross sliders persist passThreshold/shotThreshold/
        // crossThreshold, but nothing read them, so changing a slider never
        // affected the on-screen decision. Use each value as a direct bias on
        // the matching authority: 50 is neutral, 0 halves, 100 saturates.
        val smartAssistConfig = com.assistant.SmartAssistRepository.configuration()
        val shotThresholdBias =
            (smartAssistConfig.shotThreshold.coerceIn(0, 100) / 50f)
        val passThresholdBias =
            (smartAssistConfig.passThreshold.coerceIn(0, 100) / 50f)
        val crossThresholdBias =
            (smartAssistConfig.crossThreshold.coerceIn(0, 100) / 50f)

        val shotAuthority =
            (
                (
                    shootingLaneScore +
                    worldState.tacticalIntelligenceResult.confidence +
                    worldState.runtimeConfidenceCalibrationResult.calibratedConfidence
                ) * shotThresholdBias
            ).coerceIn(0f,1f)

        val passAuthority =
            (
                (
                    passingGraphScore +
                    trajectory.speed.coerceIn(0f,1f) +
                    worldState.buildUpRecognitionResult.confidence +
                    worldState.possessionStyleRecognitionResult.confidence
                ) * passThresholdBias
            ).coerceIn(0f,1f)

        val crossAuthority =
            (
                (
                    crossingLaneScore +
                    worldState.wingOverloadDetectionResult.confidence +
                    worldState.runtimeConfidenceCalibrationResult.calibratedConfidence
                ) * crossThresholdBias
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

        // SPLENDOR_AGILITY_AGC_TELEMETRY_V1 - live carrier/opponent telemetry.
        // Nearest opponent to the carrier (the earlier scan in this method).
        val agilityOpponent = nearestOppEarly
        val agilityOppX = (agilityOpponent?.x ?: telemetry.ballX).let { if (it.isFinite()) it else 0f }
        val agilityOppY = (agilityOpponent?.y ?: telemetry.ballY).let { if (it.isFinite()) it else 0f }
        val agilityPlayerX = telemetry.ballX.let { if (it.isFinite()) it else 0f }
        val agilityPlayerY = telemetry.ballY.let { if (it.isFinite()) it else 0f }
        // Real carrier-to-opponent distance for the shield engine. safeDistance is the
        // drag-vector length, not a defender distance. No opponent -> 9999f, which the
        // native layer clamps to farUnits so the proximity masks go to zero.
        val agilityOpponentDistance: Float =
            if (agilityOpponent != null) {
                val odx = agilityOppX - agilityPlayerX
                val ody = agilityOppY - agilityPlayerY
                kotlin.math.hypot(odx.toDouble(), ody.toDouble()).toFloat()
                    .let { if (it.isFinite()) it else 9999f }
            } else {
                9999f
            }

        // V4: 10-float outBuffer (escapeAngle [6], burstIntensity [7],
        // threatLevel [8], predictedDist [9] added). Opponent velocity is
        // passed as 0f/0f below — see the call-site note: TrackedPlayer
        // velocity is a per-frame EMA displacement, not px/s, and there is
        // no principled conversion into this call site's normalised 0..15
        // velocity space, so the V4 prediction here uses the player's own
        // motion and degrades gracefully. The full V4 prediction lives on
        // the AgilityContributor path, where RuntimeEntity
        // velocityXPerSecond is real px/s.
        val agilityBuffer = FloatArray(10)
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
        // Gate on native readiness: with the library unloaded the buffer
        // stays all zeros. Use an explicit neutral result instead of
        // reading fabricated zeros as a real measurement.
        // B6: oppVx/oppVy are the NEAREST OPPONENT's real velocity now
        // — TrackedPlayer.velocityXPerSecond/YPerSecond (px/s, measured
        // dt via PlayerMotionProducer) mapped into this call site's
        // normalised 0..15 space by the same producer. The old 0f/0f
        // made the closing-speed dot product compute against zero; the
        // per-frame EMA displacement (velocityX/Y) is still NOT used
        // here — it is not px/s. No opponent → 0f/0f (no motion
        // claimed), exactly as before.
        // leadTimeSec 0.18s / aggressionFactor 1.35 are the V4 defaults —
        // they must match AgilityContributor's LEAD_TIME_SEC /
        // AGGRESSION_FACTOR and the C fallbacks in native_agility_physics.c.
        val oppVelocityNorm = PlayerMotionProducer.toNormalised(
            agilityOpponent?.velocityXPerSecond ?: 0f,
            agilityOpponent?.velocityYPerSecond ?: 0f
        )
        val agilityReady = NativeBridge.nativeAgilityPhysics(
            agilityVelocityUnits, agilityOpponentDistance, movementAngleDegrees,
            safeConfidence, safeAngleRatio,
            agilityPlayerX, agilityPlayerY, agilityOppX, agilityOppY,
            oppVelocityNorm.first, oppVelocityNorm.second, 0.18f, 1.35f,
            agilityBuffer,
            100.0f, 220.0f, 500.0f, 1200.0f,
            15.0f, 4.0f, 6.0f, 3.0f, 2.0f,
            45.0f, 0.3f, 0.5f
        )
        val agility = if (agilityReady) AgilityResult(
            // SPLENDOR_AGILITY_VELOCITY_UNITS_V1 - align with the other caller
            // (AgilityContributor gates on < 0.1f). Two different thresholds on
            // the same native signal made the shield behave differently
            // depending on which path produced the frame.
            shieldActive = agilityBuffer[5] > 0.1f,
            stabilityBoost = agilityBuffer[0],
            controlRetentionBoost = agilityBuffer[1],
            turnAssist = agilityBuffer[2],
            shieldAngleDegrees = agilityBuffer[3],
            shieldDurationMs = agilityBuffer[4].toLong(),
            escapeAngleDegrees = agilityBuffer[6],
            burstIntensity = agilityBuffer[7],
            threatLevel = agilityBuffer[8],
            predictedDist = agilityBuffer[9]
        ) else AgilityResult(
            shieldActive = false,
            stabilityBoost = 0f,
            controlRetentionBoost = 0f,
            turnAssist = 0f,
            shieldAngleDegrees = 0f,
            shieldDurationMs = 0L,
            escapeAngleDegrees = 0f,
            burstIntensity = 0f,
            threatLevel = 0f,
            predictedDist = 0f
        )

        val agilityShieldRad =
            Math.toRadians(agility.shieldAngleDegrees.toDouble())
        val agilityShieldDirX =
            kotlin.math.cos(agilityShieldRad).toFloat().let { if (it.isFinite()) it else 0f }
        val agilityShieldDirY =
            kotlin.math.sin(agilityShieldRad).toFloat().let { if (it.isFinite()) it else 0f }
        // V4 escape direction: the tangential burst-dribble exit vector.
        // Same unit-vector construction as the shield direction, so the
        // burst routing below can swap one for the other without changing
        // the gesture magnitude.
        val agilityEscapeRad =
            Math.toRadians(agility.escapeAngleDegrees.toDouble())
        val agilityEscapeDirX =
            kotlin.math.cos(agilityEscapeRad).toFloat().let { if (it.isFinite()) it else 0f }
        val agilityEscapeDirY =
            kotlin.math.sin(agilityEscapeRad).toFloat().let { if (it.isFinite()) it else 0f }
        // Normalized shield-duration multiplier (V4 engine range 45..160 ms -> 1.00..1.60).
        val agilityDurationScale =
            (
                1.0f +
                (
                    (agility.shieldDurationMs.toFloat().coerceIn(45f, 160f) - 45f) / 115f
                ) * 0.6f
            ).coerceIn(1.0f, 1.6f)
        val shieldAuthority =
            agility.stabilityBoost

        // Q68-12 (batch 5b): MagneticFeetEngine.stabilize() takes a
        // PROXIMITY (close ball = large input, 0..100) — the contributor
        // passes (1 - distance/MAGNETIC_RADIUS)*100. AGC passed the raw
        // drag distance, i.e. the opposite sense (far = large input).
        // Pass the proximity-sense value instead (100 - distance, clamped).
        // TouchRecoveryEngine.recover() keeps the raw distance: its
        // parameter is pressure, and the contributor feeds it
        // defenderDensity — aligning THAT input needs a density value
        // this call site does not have, so it is left alone, not
        // silently re-sensed.
        val proximitySense = (100f - distance).toInt().coerceIn(0, 100)

        val touchRecovery =
            TouchRecoveryEngine.recover(
                (distance.toInt().coerceIn(0,100)),
                strength
            )

        val magneticFeet =
            MagneticFeetEngine.stabilize(
                proximitySense,
                strength
            )

        /*
         * Magnetic Feet contributes bounded specialist signals. Normalizing
         * here prevents it from overpowering the other arbitration engines.
         * Q68-12 (batch 5b): the true maxima are 12.0f (MAX_TOUCH /
         * MAX_INTERCEPTION / MAX_POSSESSION in MagneticFeetEngine) — the
         * old /10.0f saturated every value >= 10 to 1.0, so the top of
         * the range carried no information.
         */
        val magneticTouchAuthority =
            (magneticFeet.touchRetention / 12.0f)
                .coerceIn(0.0f, 1.0f)

        val magneticInterceptionAuthority =
            (magneticFeet.interceptionResistance / 12.0f)
                .coerceIn(0.0f, 1.0f)

        val magneticPossessionAuthority =
            (magneticFeet.possessionControl / 12.0f)
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

        // B9: throughLead (the directionless scalar) is superseded by
        // the lead VECTOR on the lane (leadX/leadY) — the scalar is kept
        // on ThroughBallLane for the confidence math, but the arbitration
        // below reads the vector, never this scalar.
        @Suppress("UNUSED_VARIABLE")
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

        // N5: record which arm supplied the cross target — the
        // 2026-11-18 off-screen endpoints were unattributable because
        // this was never logged (see the "SmartAssist queued" line).
        val crossSource = when {
            selectedCrossLane != null -> "LANE"
            selectedOpenSpace != null -> "OPEN_SPACE"
            else -> "PASS"
        }

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
        // B9: the through target is the receiver PLUS the
        // direction-aware lead vector (along the lane / receiver run) —
        // the old scalar lead subtracted from Y always led up-screen
        // regardless of attack direction (attack is along X).
        val passArbitrationX =
            if (mode == 1 && selectedThroughLane != null) throughReceiverX + selectedThroughLane.leadX
            else if (passAssist.correctedX.isFinite()) passAssist.correctedX
            else truePass.x

        val passArbitrationY =
            if (mode == 1 && selectedThroughLane != null) throughReceiverY + selectedThroughLane.leadY
            else if (passAssist.correctedY.isFinite()) passAssist.correctedY
            else truePass.y

        // Q68-93 (batch 5b): the two matrix bonuses were flat +0.1f
        // whenever the packed vector was non-zero — always on, and the
        // packed target coordinates were discarded. Scale each bonus by
        // the magnitude of the move its matrix proposes: the displacement
        // from this gesture's start to the packed target, on the file's
        // own 0..100 distance scale, capped at the old 0.1f ceiling — so
        // the stability sum can never exceed its old maximum, a zero
        // packed vector still pays 0, and a 1 px nudge no longer pays
        // the same as a full drag.
        val defensiveVectorBonus =
            if (defensiveVectorPacked == 0L) 0.0f else
                0.1f * (
                    kotlin.math.hypot(
                        (com.assistant.OmnipotentDashPressureMatrix.unpackX(defensiveVectorPacked) - startX).toDouble(),
                        (com.assistant.OmnipotentDashPressureMatrix.unpackY(defensiveVectorPacked) - startY).toDouble()
                    ).toFloat() / 100f
                ).coerceIn(0.0f, 1.0f)
        val godspeedVectorBonus =
            if (godspeedVectorPacked == 0L) 0.0f else
                0.1f * (
                    kotlin.math.hypot(
                        (com.assistant.HybridOmnipotentMatrixEngine.unpackX(godspeedVectorPacked) - startX).toDouble(),
                        (com.assistant.HybridOmnipotentMatrixEngine.unpackY(godspeedVectorPacked) - startY).toDouble()
                    ).toFloat() / 100f
                ).coerceIn(0.0f, 1.0f)

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
                // Q68-71 (batch 5b): openSideScore is a 0..4 scale
                // (NativePorts.shot out[1] = (1-pressure)*4) — the
                // contributor normalizes it /4, but this call site passed
                // it raw, so shot*36 in the native arbitration saturated
                // the +/-120 clamp on every open shot. Normalize to 0..1
                // like the contributor.
                // OPEN (Q68-71 remainder): the native dx/dy offsets are
                // always +X/+Y — every input (receiver, shot, forward,
                // recovery, stability) is non-negative, so the arbitration
                // result is only ever pushed down-right, never toward a
                // computed direction. Whether +X/+Y is the intended attack
                // direction needs a decision; the sign is not invented here.
                shot = (shotAnalysis.openSideScore / 4f).coerceIn(0f, 1f),
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
                    // V4: the combined proximity + closing-speed threat
                    // belongs in the arbitration stability sum on the same
                    // pattern as the other agility terms — a high threat
                    // under an active shield raises the stability weight of
                    // the shielded gesture, so it is not outvoted by a
                    // passing lane that ignores the defender rushing in.
                    (agility.threatLevel * 2.0f) +
                    telemetryAuthority +
                    (antiCutbackGain * 0.2f) +
                    (evadeActive * 0.2f) +
                    // SPLENDOR_F34_PRECEDENCE: parenthesize conditional bonuses so the
                    // godspeed bonus is not swallowed into the defensive else-arm.
                    // Q68-93 (batch 5b): magnitude-scaled (see above), not flat.
                    defensiveVectorBonus +
                    godspeedVectorBonus
            )

        val finalX = arbitration.finalX
        val finalY = arbitration.finalY

        // SPLENDOR_AGILITY_AGC_TELEMETRY_V1 - when the native engine reports an
        // active shield, lead the gesture target along the computed shield
        // vector. Magnitude is preserved, so the joystick deflection the engine
        // affects is unchanged; only its direction is corrected to shield the ball.
        // V4 burst routing (per-gesture rule — AGC runs once per discrete
        // gesture, not per frame, so there is no latch here; the contributor
        // path holds the hysteresis latch instead): a burst exit
        // (burstIntensity > 0.75) routes along the escape vector, an active
        // shield along the shield vector, otherwise the arbitration target
        // stands. The 0.75 threshold matches AgilityBurstLatch.BURST_ON so
        // both paths agree on when a burst is happening.
        val finalDeltaX = finalX - startX
        val finalDeltaY = finalY - startY
        val finalMagnitude =
            kotlin.math.hypot(finalDeltaX.toDouble(), finalDeltaY.toDouble()).toFloat()
        val burstActiveForTarget =
            agility.shieldActive && agility.burstIntensity > 0.75f &&
                agilityEscapeDirX.isFinite() && agilityEscapeDirY.isFinite()
        val shieldActiveForTarget =
            agility.shieldActive && agilityShieldDirX.isFinite() && agilityShieldDirY.isFinite()
        val routedEndX =
            when {
                burstActiveForTarget && finalMagnitude > 0.001f -> startX + agilityEscapeDirX * finalMagnitude
                shieldActiveForTarget && finalMagnitude > 0.001f -> startX + agilityShieldDirX * finalMagnitude
                else -> finalX
            }
        val routedEndY =
            when {
                burstActiveForTarget && finalMagnitude > 0.001f -> startY + agilityEscapeDirY * finalMagnitude
                shieldActiveForTarget && finalMagnitude > 0.001f -> startY + agilityShieldDirY * finalMagnitude
                else -> finalY
            }
        val routedDuration =
            (boostedDuration * agilityDurationScale).toLong().coerceAtLeast(15L)

        val safeRoutedEndXInitial = if (routedEndX.isFinite()) routedEndX else endX
        val safeRoutedEndYInitial = if (routedEndY.isFinite()) routedEndY else endY
        val isPanic = SmartAssistRepository.panicActive()
        // emergencyClearanceVector is exactly Pair(startX, targetY). Keep its
        // public helper contract, but use its scalar identity here to avoid a
        // per-panic-gesture Pair allocation.
        val panicEndX = safeRoutedEndXInitial
        val panicEndY = safeRoutedEndYInitial
        val finalDuration = if (isPanic) emergencyClearanceDuration() else routedDuration

        val safeRoutedEndX = if (panicEndX.isFinite()) panicEndX else endX
        val safeRoutedEndY = if (panicEndY.isFinite()) panicEndY else endY
        val safeRoutedDuration = finalDuration.coerceIn(15L, 10000L)
        val request =
            ExecutionRequest(
                source = ExecutionSource.SMART_ASSIST,
                phase = decision.mode,
                // Q68-94 (batch 5b): the legacy path carried actionClass=null,
                // so executeDirectRequest skipped the cooling check entirely
                // (only GOALKEEPER-source requests had a fallback). Map the
                // decided mode — the same 0/1/2 the native arbitration
                // branches on (default=cross, 1=pass, 2=shot) — and panic,
                // whose clearance duration makes it a DEFEND gesture.
                actionClass = if (isPanic) ActionClass.DEFEND else when (mode) {
                    2 -> ActionClass.SHOT
                    1 -> ActionClass.PASS
                    else -> ActionClass.CROSS
                },
                // no59 ITEM 1: the legacy injectWinningVector path has no
                // contributing engine (it arbitrates inside this controller
                // from the legacy decision) — the controller itself is the
                // honest producer label for the dispatch log.
                engine = "ActiveGestureController",
                startX = startX,
                startY = startY,
                endX = safeRoutedEndX,
                endY = safeRoutedEndY,
                duration = safeRoutedDuration
            )
        val accepted =
            HybridExecutionTerminal.route(request)

        RuntimeLogger.log(
            "SmartAssist queued mode=$mode distance=${distance.toInt()} strength=$strength accepted=$accepted panic=${SmartAssistRepository.panicActive()} cross=(${crossTargetX.toInt()},${crossTargetY.toInt()}) crossSrc=$crossSource",
            "SMART_ASSIST"
        )
        // V4 burst log: the burst exit is the one event on this path the
        // theater/dashboard can read (predictedDist's consumer here).
        if (burstActiveForTarget) {
            RuntimeLogger.log(
                "Agility BURST exit threat=${agility.threatLevel} predictedDist=${agility.predictedDist} burst=${agility.burstIntensity}",
                "SMART_ASSIST"
            )
        }
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




