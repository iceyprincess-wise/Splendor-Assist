object VisionCore {

    fun process(
        frame: FrameNormalizer.NormalizedFrame
    ): GameStateSnapshot {

        val blobs =
            VisionPreprocessor.process(frame)

        val filteredBlobs =
            NoiseFilter.filter(blobs)

val heuristicCandidate =
    BallCandidateEngine.select(
        filteredBlobs
    )

/*
 * Task C: trained detector first. When a real TFLite model is loaded AND
 * has produced a real on-device inference, its ball wins this frame; the
 * heuristic candidate above is still computed every frame as the always-on
 * fallback and cross-check reference. With no model asset present the
 * engine returns null and behaviour is IDENTICAL to the pure-heuristic
 * pipeline - nothing is faked in either direction.
 */
val ballCandidate =
    TrainedDetectionEngine.ballCandidateOrNull(frame, heuristicCandidate)
        ?: heuristicCandidate

val ball =
    BallDetector.detect(
        ballCandidate
    )

val goal =
    GoalDetector.detect(
        filteredBlobs
    )

val field =
    FieldLineDetector.detect(
        filteredBlobs
    )

val motion =
    MotionTracker.update(
        ball
    )

// Single writer of ball telemetry: publishes only on a real detection so
// FrameAssembler can mark the frame trusted and all contributors activate.
BallTelemetryBridge.publish(ball)

val players =
    PlayerDetector.detect(filteredBlobs)

val goalkeeper =
    GoalkeeperDetector.detect(
        filteredBlobs
    )

val state =
    GameStateFusion.fuse(
        ball,
        motion,
        players,
        goalkeeper,
        goal,
        field
    )

SceneTracker.update(
    state,
    players
)

  val scene =
      SceneTracker.current()

  // Keep the telemetry repository goalkeeper slot live. SceneTracker is the
  // only component that actually tracks the keeper, but its result was never
  // copied into TelemetryRepository, so goalkeeperX/Y stayed 0 for every
  // consumer. Publish the real tracked position; when the keeper is not
  // visible publish 0,0 so confidence collapses instead of holding a stale
  // position.
  if (scene.goalkeeperVisible) {
      TelemetryCoordinator.updateGoalkeeperPosition(
          scene.goalkeeperX,
          scene.goalkeeperY
      )
  } else {
      TelemetryCoordinator.updateGoalkeeperPosition(0f, 0f)
  }

  val closestPlayer =
      ClosestPlayerEngine.compute(
          ball,
          scene
      )

  val ownership =
      BallOwnershipEngine.compute(
          ball,
          scene
      )

  val possession =
      BallPossessionEngine.compute(
          ownership
      )

  val attacker =
      ActiveAttackerEngine.compute(
          scene,
          possession
      )

  val defender =
      ActiveDefenderEngine.compute(
          scene,
          attacker
      )

  val formation =
      FormationEngine.estimate(
          scene
      )

  val teamShape =
      TeamShapeEngine.compute(
          scene
      )

  val defensiveLine =
      DefensiveLineEngine.compute(
          scene
      )

  val offensiveLine =
      OffensiveLineEngine.compute(
          scene
      )

  val occupancy =
      SpaceOccupancyEngine.compute(
          scene,
          frame.width.toFloat(),
          frame.height.toFloat()
      )

  val pressure =
      PressureFieldEngine.compute(
          scene,
          frame.width.toFloat(),
          frame.height.toFloat()
      )

  val tacticalMapResult =
      TacticalMapGenerationEngine.compute(
          scene,
          occupancy,
          pressure,
          teamShape,
          defensiveLine,
          offensiveLine
      )

  val defensiveCompactnessResult =
      DefensiveCompactnessEngine.compute(
          scene,
          defensiveLine,
          teamShape
      )

  val wingOverloadDetectionResult =
      WingOverloadDetectionEngine.compute(
          scene,
          occupancy,
          pressure
      )

  val centralOverloadDetectionResult =
      CentralOverloadDetectionEngine.compute(
          scene,
          occupancy,
          pressure
      )

  val passingGraph =
      PassingLaneGraphEngine.build(
          scene,
          pressure
      )

  val throughBallAnalysis =
      if (!InterceptionRuntimeRegistry.armed) ThroughBallLaneAnalysis() else ThroughBallLaneAnalysisEngine.analyze(
          passingGraph
      )

  val crossingLaneAnalysis =
      if (!InterceptionRuntimeRegistry.armed) CrossingLaneAnalysis() else CrossingLaneAnalysisEngine.analyze(
          passingGraph
      )

  val shootingLaneAnalysis =
      if (!InterceptionRuntimeRegistry.armed) ShootingLaneAnalysis() else ShootingLaneAnalysisEngine.analyze(
          scene,
          passingGraph
      )

  val blockedLanePredictionAnalysis =
      if (!InterceptionRuntimeRegistry.armed) BlockedLanePredictionAnalysis() else BlockedLanePredictionEngine.analyze(
          passingGraph
      )

  val defenderInterceptionPredictionAnalysis =
      if (!InterceptionRuntimeRegistry.armed) DefenderInterceptionPredictionAnalysis() else DefenderInterceptionPredictionEngine.analyze(
          scene,
          passingGraph
      )

  val openSpaceDetectionResult =
      if (!InterceptionRuntimeRegistry.armed) OpenSpaceDetectionResult() else OpenSpaceDetectionEngine.analyze(
          occupancy,
          pressure,
          frame.width.toFloat(),
          frame.height.toFloat()
      )

  val receiverRankingResult =
      if (!InterceptionRuntimeRegistry.armed) ReceiverRankingResult() else ReceiverRankingEngine.analyze(
          passingGraph
      )

  val runPredictionResult =
      if (!InterceptionRuntimeRegistry.armed) RunPredictionResult() else RunPredictionEngine.analyze(
          scene
      )

  val overlapDetectionResult =
      if (!InterceptionRuntimeRegistry.armed) OverlapDetectionResult() else OverlapDetectionEngine.analyze(
          runPredictionResult
      )

  val counterattackDetectionResult =
      if (!InterceptionRuntimeRegistry.armed) CounterattackDetectionResult() else CounterattackDetectionEngine.analyze(
          scene,
          teamShape,
          offensiveLine
      )

  val fastBreakDetectionResult =
      FastBreakDetectionEngine.analyze(
          scene
      )

  val pressingRecognitionResult =
      PressingRecognitionEngine.analyze(
          pressure,
          defensiveCompactnessResult,
          formation
      )

  val counterPressRecognitionResult =
      CounterPressRecognitionEngine.analyze(
          scene,
          possession,
          pressure
      )

  val buildUpRecognitionResult =
      BuildUpRecognitionEngine.analyze(
          formation,
          teamShape,
          passingGraph
      )

  val possessionStyleRecognitionResult =
      PossessionStyleRecognitionEngine.analyze(
          possession,
          passingGraph,
          pressure
      )

  val tacticalAnalyticsResult =
      TacticalAnalyticsEngine.analyze(
          tacticalMapResult,
          defensiveCompactnessResult,
          wingOverloadDetectionResult,
          centralOverloadDetectionResult,
          pressingRecognitionResult,
          counterPressRecognitionResult,
          buildUpRecognitionResult,
          possessionStyleRecognitionResult
      )

  val tacticalBehaviorRecognitionResult =
      TacticalBehaviorRecognitionEngine.analyze(
          tacticalAnalyticsResult,
          formation,
          teamShape
      )

      val previousTemporalMemory =
          Phase3WorldStateStore.current().temporalMemoryState

      val temporalMemoryState =
          TemporalMemoryEngine.update(
              previousTemporalMemory,
              tacticalBehaviorRecognitionResult.confidence
          )

  val tacticalIntelligenceResult =
      TacticalIntelligenceEngine.analyze(
          tacticalAnalyticsResult,
          tacticalBehaviorRecognitionResult,
          state,
          temporalMemoryState,
          tacticalMapResult
      )

      val opponentBehaviourLearningResult =
          OpponentBehaviourLearningEngine.analyze(
              tacticalIntelligenceResult,
              state,
              temporalMemoryState
          )

      val playerTendencyLearningResult =
          PlayerTendencyLearningEngine.analyze(
              tacticalIntelligenceResult,
              state,
              temporalMemoryState
          )

      val preferredPassingLaneLearningResult =
          PreferredPassingLaneLearningEngine.analyze(
              passingGraph,
              tacticalIntelligenceResult,
              temporalMemoryState
          )

      val shootingHabitLearningResult =
          ShootingHabitLearningEngine.analyze(
              shootingLaneAnalysis,
              tacticalIntelligenceResult,
              temporalMemoryState
          )

      val formationAdaptationResult =
          FormationAdaptationEngine.analyze(
              tacticalIntelligenceResult,
              opponentBehaviourLearningResult,
              playerTendencyLearningResult,
              temporalMemoryState
          )

      val runtimeConfidenceCalibrationResult =
          RuntimeConfidenceCalibrationEngine.analyze(
              tacticalIntelligenceResult,
              formationAdaptationResult,
              preferredPassingLaneLearningResult,
              shootingHabitLearningResult,
              temporalMemoryState
          )

      val onlineParameterAdaptationResult =
          OnlineParameterAdaptationEngine.analyze(
              runtimeConfidenceCalibrationResult,
              state,
              temporalMemoryState
          )

val offsideRiskEstimationResult =
      OffsideRiskEstimationEngine.analyze(
          passingGraph
      )

    Phase3WorldStateStore.update(
        Phase3WorldState(
            closestPlayer = closestPlayer,
            ownership = ownership,
            possession = possession,
            attacker = attacker,
            defender = defender,
            formation = formation,
            teamShape = teamShape,
            defensiveLine = defensiveLine,
            offensiveLine = offensiveLine,
            occupancy = occupancy,
            pressure = pressure,
            tacticalMapResult = tacticalMapResult,
            defensiveCompactnessResult = defensiveCompactnessResult,
            wingOverloadDetectionResult = wingOverloadDetectionResult,
            centralOverloadDetectionResult = centralOverloadDetectionResult,
            passingGraph = passingGraph,
            throughBallAnalysis = throughBallAnalysis,
            crossingLaneAnalysis = crossingLaneAnalysis,
            shootingLaneAnalysis = shootingLaneAnalysis,
            blockedLanePredictionAnalysis = blockedLanePredictionAnalysis,
            defenderInterceptionPredictionAnalysis = defenderInterceptionPredictionAnalysis,
            openSpaceDetectionResult = openSpaceDetectionResult,
            receiverRankingResult = receiverRankingResult,
            runPredictionResult = runPredictionResult,
            overlapDetectionResult = overlapDetectionResult,
            counterattackDetectionResult = counterattackDetectionResult,
            fastBreakDetectionResult = fastBreakDetectionResult,
            offsideRiskEstimationResult = offsideRiskEstimationResult,
            pressingRecognitionResult = pressingRecognitionResult,
            counterPressRecognitionResult = counterPressRecognitionResult,
            buildUpRecognitionResult = buildUpRecognitionResult,
            possessionStyleRecognitionResult = possessionStyleRecognitionResult,
            tacticalAnalyticsResult = tacticalAnalyticsResult,
            tacticalBehaviorRecognitionResult = tacticalBehaviorRecognitionResult,
            tacticalIntelligenceResult = tacticalIntelligenceResult,
            opponentBehaviourLearningResult = opponentBehaviourLearningResult,
            playerTendencyLearningResult = playerTendencyLearningResult,
            preferredPassingLaneLearningResult = preferredPassingLaneLearningResult,
            shootingHabitLearningResult = shootingHabitLearningResult,
            formationAdaptationResult = formationAdaptationResult,
            runtimeConfidenceCalibrationResult = runtimeConfidenceCalibrationResult,
            onlineParameterAdaptationResult = onlineParameterAdaptationResult,
            temporalMemoryState = temporalMemoryState
)
    )

return state
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
/* ======
VisionCore Anchor
====== */

/* ========
VisionDebugOverlay
======== */
