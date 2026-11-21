package com.assistant





































import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.runtime.*


























import kotlin.math.*




























object VisionCore {

    fun process(
        frame: FrameNormalizer.NormalizedFrame
    ): GameStateSnapshot {

        // B7: real frame-time measurement — the vision latency producer.
        // process() IS the frame processing; its wall time is the latency
        // VisionLatencyMonitor.update() was waiting for (Q68-57: measure,
        // never derive from FPS). Reported on both exits below.
        val processStartedNanos = System.nanoTime()

        val blobs =
            VisionPreprocessor.process(frame)

        val filteredBlobs =
            NoiseFilter.filter(blobs)

val heuristicCandidate =
    BallCandidateEngine.select(
        filteredBlobs,
        // Batch 6b: temporal prior from the detector's last lock-on.
        // Null until the first lock-on — select() is unchanged then.
        BallDetector.lastKnownPosition()
    )

/*
 * Task C (queue item 21, v3 TFLite detector): trained detector joins,
 * never replaces. The inference runs ASYNC on a dedicated loop — never
 * inside process() — so the frame below only (a) offers its snapshot
 * to that loop and (b) reads the latest result through the fusion
 * gates. The heuristic candidate above is still computed every frame
 * as the always-on fallback and cross-check reference, and it owns the
 * frame whenever the trained result is absent, stale, weak, or
 * divergent. With no model file placed the engine is MISSING_MODEL and
 * behaviour is IDENTICAL to the pure-heuristic pipeline - nothing is
 * faked in either direction. The gate is VisionConfiguration.
 * trainedDetectionEnabled (default true — file presence is the real
 * opt-in); the CRITICAL-memory ball-only branch below still benefits,
 * because the ball was fused before the branch.
 */
val trainedDetectionEnabled = VisionConfigurationEngine.current().trainedDetectionEnabled
if (trainedDetectionEnabled) {
    TrainedDetectionEngine.submitFrame(frame)
}
val ballCandidate =
    if (trainedDetectionEnabled) {
        TrainedDetectionEngine.fusedBall(
            heuristicCandidate, frame.width, frame.height, frame.metadata.timestampNanos
        ) ?: heuristicCandidate
    } else {
        heuristicCandidate
    }

val ball =
    BallDetector.detect(
        ballCandidate
    )

// Batch 6e (carried from 6d File 438): ConfidenceHeatmap.update() had
// zero callers, so the `confidence` field in the heatmap state was
// permanently 0f while RuntimePerformanceCoordinator reads it into the
// live performance state. Feed it the ball detection confidence of
// this frame — the per-frame detection confidence. Full-vision path
// only; under CRITICAL memory the ball-only path runs instead and the
// field keeps its last value.
ConfidenceHeatmap.update(ball.confidence)

// L1 (batch 1): CRITICAL memory — the ball-only path. This is the first
// live caller of MemoryCaptureGateEngine.shouldSkipFullVision() (zero
// callers before this round). Full VisionCore is skipped: only the ball
// (+ goal in BALL_GOAL mode, + players in BALL_PLAYERS mode) is
// detected, the ball is published so the keeper scan + FrameAssembler
// trust path stay live, and the world store keeps its last full-vision
// values (stale possession under CRITICAL is the documented trade-off —
// better than dropping vision entirely). D1: capture is NOT slowed;
// this path is strictly cheaper than full vision.
if (MemoryCaptureGateEngine.shouldSkipFullVision()) {
    val ballOnlyState = processBallOnly(filteredBlobs, ball)
    reportVisionLatency(processStartedNanos)
    return ballOnlyState
}

val goal =
    GoalDetector.detect(
        filteredBlobs
    )

// L2 (batch 1): anchor the detected goal to its posts. The calibration
// is stored in GoalPostCalibrationEngine.latest() for the keeper router.
GoalPostCalibrationEngine.calibrate(
    goal,
    com.assistant.vision.CameraProfile.captureWidthOrFallback(),
    com.assistant.vision.CameraProfile.captureHeightOrFallback()
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

val heuristicPlayers =
    PlayerDetector.detect(filteredBlobs)

// Queue item 21: trained players MERGE, never replace — a trained
// player with no heuristic match within 40 px is added; matched pairs
// confirm the heuristic one. Full-vision path only; the CRITICAL-memory
// ball-only path below stays heuristic-only by design (degraded path).
val players =
    if (trainedDetectionEnabled) {
        TrainedDetectionEngine.mergedPlayers(
            heuristicPlayers, frame.width, frame.height, frame.metadata.timestampNanos
        )
    } else {
        heuristicPlayers
    }

val heuristicGoalkeeper =
    GoalkeeperDetector.detect(
        filteredBlobs
    )

// Queue item 21: trained keeper wins when fresh + confident (>= 0.50),
// via the fusion seam — GoalkeeperDetector.detect stays blob-only by
// design and is untouched. Full-vision path only (same reason as above).
val goalkeeper =
    if (trainedDetectionEnabled) {
        TrainedDetectionEngine.fusedKeeper(
            frame.width, frame.height, frame.metadata.timestampNanos
        ) ?: heuristicGoalkeeper
    } else {
        heuristicGoalkeeper
    }

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
          scene.goalkeeperY,
          scene.goalkeeperConfidence
      )
  } else {
      TelemetryCoordinator.updateGoalkeeperPosition(0f, 0f, 0f)
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

  // The possession attacker is ours; defense needs the observed opponent owner.
  val defensiveAttacker = if (ownership.hasOwner && ownership.owner?.isUserTeam == false)
      ActiveAttackerResult(true, ownership.owner, ownership.ownerIndex, ownership.confidence)
      else attacker
  val defender = ActiveDefenderEngine.compute(scene, defensiveAttacker)
  AttackAxisModel.observe(scene, possession.hasPossession, frame.width.toFloat())

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

  // L13 (batch 1): the user line + the tight defensive stance. This is
  // computeUserLine's first live caller (zero callers before this round).
  // The stance is stored on the world state for FrameAssembler; the
  // D14 tight-line/tight-midfield clamps live in DefensiveStanceProducer.
  val userLine =
      DefensiveLineEngine.computeUserLine(
          scene
      )

  val defensiveStance =
      DefensiveStanceProducer.compute(
          userLine,
          ball.x,
          com.assistant.vision.CameraProfile.captureWidthOrFallback()
      )

  // B4: the low-block wiring — the FIRST live caller of
  // LowBlockContainmentEngine (zero callers before this round). The
  // defenders are our tracked players; the movements are published to
  // the world store (frame → DefenseContributor). QD-3 is UNRESOLVED,
  // so the frame's lowBlockActive gate stays false and no consumer
  // applies these movements yet — they are computed + published, not
  // acted on, until Ed defines the X-geometry.
  val lowBlockMovements =
      run {
          val defenders = scene.trackedPlayers.filter { it.isUserTeam }
          if (defenders.isEmpty()) {
              emptyList<com.assistant.runtime.LowBlockMovement>()
          } else {
              val result = LowBlockContainmentEngine.applyLowBlockContainment(
                  ball.x,
                  ball.y,
                  com.assistant.vision.CameraProfile.captureWidthOrFallback(),
                  com.assistant.vision.CameraProfile.captureHeightOrFallback(),
                  defenders.map { Pair(it.x, it.y) }
              )
              result.movements.mapIndexed { index, target ->
                  com.assistant.runtime.LowBlockMovement(
                      defenderId = defenders[index].id,
                      targetX = target.first,
                      targetY = target.second
                  )
              }
          }
      }

  // L14 (batch 1): the tracked defender's home on the tight line. The
  // stopper anchor in DefenseContributor reads it off the frame.
  val defenderHome =
      run {
          val trackedDef = defender.defender
          if (defender.found && trackedDef != null) {
              HomePositionProducer.homePosition(
                  trackedDef.y,
                  userLine,
                  com.assistant.vision.CameraProfile.captureWidthOrFallback(),
                  com.assistant.vision.CameraProfile.captureHeightOrFallback()
              )
          } else HomePosition(false)
      }

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

  // Midfield (queue item 5, batch midfield): the measured midfield
  // line (middle band of user players, between the user line's back
  // edge and the offensive mean) + its compactness over the band
  // players only. Both edges exist by here (userLine above,
  // offensiveLine above); the compactness call sits next to the
  // defensive one, same cadence (every frame, unconditional).
  val midfieldLine =
      MidfieldLineEngine.compute(
          scene,
          userLine,
          offensiveLine
      )

  val midfieldCompactnessResult =
      MidfieldCompactnessEngine.compute(
          scene,
          midfieldLine,
          TeamShapeEngine.computeFor(
              MidfieldLineEngine.bandPlayers(scene, userLine, offensiveLine)
          )
      )

  val wingOverloadDetectionResult =
      WingOverloadDetectionEngine.compute(
          scene,
          occupancy,
          pressure
      )

  val centralOverloadDetectionResult =
      CentralOverloadDetectionEngine.compute(
          scene
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
      if (!InterceptionRuntimeRegistry.armed) {
          // No67-D24: publish the empty result so the snapshot does not
          // keep reporting the last armed period's lanes.
          CrossingLaneAnalysisEngine.reset()
          CrossingLaneAnalysis()
      } else CrossingLaneAnalysisEngine.analyze(
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
          formation,
          // No68-I-D24: pressing is judged at the ball, not by the mean of
          // the whole field (the mean is inverted — see the engine).
          ballX = scene.trackedBallX,
          ballY = scene.trackedBallY,
          frameWidth = frame.width.toFloat(),
          frameHeight = frame.height.toFloat(),
          ballVisible = scene.trackedBallVisible
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
              temporalMemoryState,
              // Queue item 8 (Gap 2): feed NetProbeEngine's MEASURED RTT
              // (EMA of TCP-connect medians, 2-5 s cadence) instead of the
              // 15.0f default the engine used every frame until now.
              // effectivePingMs() keeps the default only while rtt is 0f
              // (never measured yet / just wiped on link change).
              // Mode-blind on purpose (Ed's decision 1): no mode input.
              networkPingMs = OnlineParameterAdaptationEngine.effectivePingMs(NetProbeEngine.rtt)
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
            defensiveStance = defensiveStance,
            // B5: the user line itself, as a store field (first publish).
            userLine = userLine,
            // B4: the low-block movement list (computed above).
            lowBlockMovements = lowBlockMovements,
            defenderHome = defenderHome,
            offensiveLine = offensiveLine,
            occupancy = occupancy,
            pressure = pressure,
            tacticalMapResult = tacticalMapResult,
            defensiveCompactnessResult = defensiveCompactnessResult,
            // Midfield (queue item 5, batch midfield).
            midfieldLine = midfieldLine,
            midfieldCompactnessResult = midfieldCompactnessResult,
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

reportVisionLatency(processStartedNanos)
return state
    }

    // B7: the vision-latency producer — reports this process() call's
    // measured wall time (ms) to VisionLatencyMonitor. A non-positive
    // or absurd reading is never reported (the monitor guards anyway).
    private fun reportVisionLatency(processStartedNanos: Long) {
        val elapsedMs = (System.nanoTime() - processStartedNanos) / 1_000_000f
        if (elapsedMs.isFinite() && elapsedMs >= 0f) {
            try { VisionLatencyMonitor.update(elapsedMs) } catch (_: Throwable) { }
        }
    }

    // L1 (batch 1): the CRITICAL-memory ball-only path — the cheap half of
    // process(). Only the ball is always detected; the goal is detected
    // only in BALL_GOAL mode, players only in BALL_PLAYERS mode (D3
    // zone-conditional). Field lines, keeper, formation, lanes and all
    // learning/tactical engines are skipped; the fused state carries
    // undetected defaults for them and SceneTracker still updates, so
    // the keeper scan + FrameAssembler trust path keep a live ball. The
    // Phase3 world store is NOT rewritten here — it keeps its last
    // full-vision values. The keeper telemetry slot is published 0,0,0
    // (no keeper was detected on this path) so confidence collapses
    // instead of holding a stale keeper position.
    @Volatile private var lastBallOnlyLogMs: Long = 0L

    private fun processBallOnly(
        filteredBlobs: List<ConnectedComponentEngine.Blob>,
        ball: BallDetectionResult
    ): GameStateSnapshot {
        val width = com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val height = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        val mode = BallOnlyDetector.modeFor(
            ball.x, width, AttackAxisModel.directionIfConfident()
        )
        val goal = if (mode == BallOnlyMode.BALL_GOAL) {
            val detected = GoalDetector.detect(filteredBlobs)
            GoalPostCalibrationEngine.calibrate(detected, width, height)
            detected
        } else {
            GoalDetectionResult(false, 0f, 0f, 0f, 0f, 0f)
        }
        val players = if (mode == BallOnlyMode.BALL_PLAYERS) {
            PlayerDetector.detect(filteredBlobs)
        } else {
            PlayerDetectionResult(false, 0, 0f, emptyList())
        }
        val result = BallOnlyDetector.select(
            ball.x, ball.y, ball.confidence,
            goal.rightX - goal.leftX, goal.detected,
            players.detections.size, width,
            AttackAxisModel.directionIfConfident()
        )
        val motion = MotionTracker.update(ball)
        BallTelemetryBridge.publish(ball)
        val state = GameStateFusion.fuse(
            ball,
            motion,
            players,
            GoalkeeperDetectionResult(false, 0f, 0f, 0f),
            goal,
            FieldLineDetectionResult(false, false, false, false, 0f)
        )
        SceneTracker.update(state, players)
        TelemetryCoordinator.updateGoalkeeperPosition(0f, 0f, 0f)
        try {
            val now = System.currentTimeMillis()
            if (now - lastBallOnlyLogMs >= 1000L) {
                lastBallOnlyLogMs = now
                com.assistant.diagnostic.RuntimeLogger.log(
                    "BALL_ONLY mode=${result.mode} ball=(${String.format("%.1f", result.ballX)}," +
                        "${String.format("%.1f", result.ballY)}) goal=${result.goalIncluded} " +
                        "players=${result.nearestPlayerCount}",
                    "VISION"
                )
            }
        } catch (_: Throwable) { }
        return state
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
}

/* ========
VisionDebugOverlay
======== */
