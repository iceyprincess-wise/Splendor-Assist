package com.assistant

import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import java.util.Locale

object VisionCore {

    fun process(
        frame: FrameNormalizer.NormalizedFrame
    ): GameStateSnapshot {

        // B7: real frame-time measurement — the vision latency producer.
        // process() IS the frame processing; its wall time is the latency
        // VisionLatencyMonitor.update() was waiting for (Q68-57: measure,
        // never derive from FPS). Reported on both exits below.
        val processStartedNanos = System.nanoTime()

        // Phase 0 (all-round upgrade plan): per-reader timing. Each
        // reader/engine group below is timed into VisionStageProfiler
        // (recordReader, beside reportVisionLatency) — no new logging
        // system, no behaviour change.
        val tPreprocess = System.nanoTime()
        val blobs =
            VisionPreprocessor.process(frame)
        recordReader("preprocess", tPreprocess)

        val tFilter = System.nanoTime()
        val filteredBlobs =
            NoiseFilter.filter(blobs)
        recordReader("filter", tFilter)

val tBallCandidate = System.nanoTime()
val heuristicCandidate =
    BallCandidateEngine.select(
        filteredBlobs,
        // Batch 6b: temporal prior from the detector's last lock-on.
        // Null until the first lock-on — select() is unchanged then.
        BallDetector.lastKnownPosition()
    )
recordReader("ballCandidate", tBallCandidate)

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

val tBallDetect = System.nanoTime()
val ball =
    BallDetector.detect(
        ballCandidate
    )
recordReader("ballDetect", tBallDetect)

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
    val tBallOnly = System.nanoTime()
    val ballOnlyState = processBallOnly(filteredBlobs, ball, frame.width.toFloat(), frame.height.toFloat(), frame)
    recordReader("ballOnly", tBallOnly)
    reportVisionLatency(processStartedNanos)
    return ballOnlyState
}

val tGoal = System.nanoTime()
val goal = detectGoal(frame, filteredBlobs)
recordReader("goal", tGoal)

// L2 (batch 1): anchor the detected goal to its posts. The calibration
// is stored in GoalPostCalibrationEngine.latest() for the keeper router.
// Item 12 (C10): the blobs are in FRAME coordinates, so the
// calibration must be checked against the frame's own size — the
// 1650x720 fallback made a real frame's posts read outside-frame.
GoalPostCalibrationEngine.calibrate(
    goal,
    frame.width.toFloat(),
    frame.height.toFloat()
)

val tField = System.nanoTime()
val field =
    FieldLineDetector.detect(
        filteredBlobs
    )
recordReader("field", tField)

val tMotion = System.nanoTime()
val motion =
    MotionTracker.update(
        ball
    )
recordReader("motion", tMotion)

// Single writer of ball telemetry: publishes only on a real detection so
// FrameAssembler can mark the frame trusted and all contributors activate.
BallTelemetryBridge.publish(ball)

val tPlayers = System.nanoTime()
val heuristicPlayers =
    PlayerDetector.detect(filteredBlobs)
recordReader("players", tPlayers)

// RING READER (ring-reader build, 2026-11-25): direct pixel read of
// the controlled-player ring in both measured states (cyan when
// dashing, pale white-green when not) — the old saturated-blue ring
// branch in PlayerDetector never fires on the real ring (colour
// never OPPONENT, aspect 0.25-0.45 < 0.60). The reader's output is
// merged here, at the same point the branch fed the isControlled
// flag, so every downstream consumer (trained merge, fusion,
// SceneTracker, FrameAssembler cursor) sees it exactly as before.
// Fail-open: a null ring (or a reader throw) leaves the players
// untouched. Full-vision path only — the CRITICAL-memory ball-only
// path stays heuristic-only by design (degraded path).
//
// RING STATE -> DASH PROXY (#45): the same read result also feeds
// isDashing (CYAN = dashing/in possession, PALE = not) — published
// to the world store below so FrameAssembler can carry it into the
// frame. The state's exact trigger is UNVERIFIED: the footage
// evidence is possession-based (cyan with the ball, pale without)
// and Ed's evidence is Dash-based (blue when Dash is pressed). They
// correlate in play but are not the same signal, so this is a
// dash/possession PROXY — never "cyan = Dash pressed" as fact.
// Null ring (or a reader throw) -> false. No second read: the read
// already happens once per frame here.
val tRing = System.nanoTime()
val ringRead: ControlledRingReader.RingRead? = try {
    ControlledRingReader.read(frame.buffer, frame.width, frame.height, frame.rowStride, frame.pixelStride)
} catch (_: Throwable) { null }
recordReader("ring", tRing)
val isDashing = ringRead?.state == ControlledRingReader.RingState.CYAN
val heuristicPlayersWithRing = try {
    ControlledRingReader.mergeInto(
        heuristicPlayers,
        ringRead,
        frame.height.toFloat()
    )
} catch (_: Throwable) { heuristicPlayers }

// Queue item 21: trained players MERGE, never replace — a trained
// player with no heuristic match within 40 px is added; matched pairs
// confirm the heuristic one. Full-vision path only; the CRITICAL-memory
// ball-only path below stays heuristic-only by design (degraded path).
val players =
    if (trainedDetectionEnabled) {
        TrainedDetectionEngine.mergedPlayers(
            heuristicPlayersWithRing, frame.width, frame.height, frame.metadata.timestampNanos
        )
    } else {
        heuristicPlayersWithRing
    }

val tKeeper = System.nanoTime()
val heuristicGoalkeeper =
    GoalkeeperDetector.detect(
        filteredBlobs
    )
recordReader("keeper", tKeeper)

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

val tFusion = System.nanoTime()
val state =
    GameStateFusion.fuse(
        ball,
        motion,
        players,
        goalkeeper,
        goal,
        field
    )
recordReader("fusion", tFusion)

val tSceneTracker = System.nanoTime()
SceneTracker.update(
    state,
    players
)
recordReader("sceneTracker", tSceneTracker)

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

  val tEnginesDef = System.nanoTime()
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
      ActiveAttackerResult(true, ownership.owner, ownership.ownerIndex)
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
  // the world store (frame → DefenseContributor). Queue item 9 (L2)
  // resolved QD-3: the engine is X-axis now, and its own active flag
  // (not list non-emptiness) drives the frame gate.
  // Our goal on the X axis — MarkingEngine convention: the detected
  // goal centre when a goal is detected, else the attack-direction
  // edge (attacking left puts our goal at the right edge). Neither
  // available → ownGoalKnown=false → the engine holds position
  // rather than shaping a line at a guessed side.
  val ownGoalX: Float
  val ownGoalKnown: Boolean
  run {
      val attackDir = AttackAxisModel.directionIfConfident()
      when {
          scene.goalDetected -> {
              ownGoalX = (scene.goalLeftX + scene.goalRightX) * 0.5f
              ownGoalKnown = true
          }
          attackDir < 0 -> {
              ownGoalX = com.assistant.vision.CameraProfile.captureWidthOrFallback()
              ownGoalKnown = true
          }
          attackDir > 0 -> {
              ownGoalX = 0f
              ownGoalKnown = true
          }
          else -> {
              ownGoalX = 0f
              ownGoalKnown = false
          }
      }
  }
  val lowBlockDefenders = scene.trackedPlayers.filter { it.isUserTeam }
  val lowBlockResult = if (lowBlockDefenders.isEmpty()) {
      DefensiveContainmentResult(emptyList(), active = false)
  } else {
      LowBlockContainmentEngine.applyLowBlockContainment(
          ball.x,
          ball.y,
          com.assistant.vision.CameraProfile.captureWidthOrFallback(),
          com.assistant.vision.CameraProfile.captureHeightOrFallback(),
          lowBlockDefenders.map { Pair(it.x, it.y) },
          ownGoalX = ownGoalX,
          ownGoalKnown = ownGoalKnown
      )
  }
  val lowBlockMovements =
      lowBlockResult.movements.mapIndexed { index, target ->
          com.assistant.runtime.LowBlockMovement(
              defenderId = lowBlockDefenders[index].id,
              targetX = target.first,
              targetY = target.second
          )
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
  recordReader("enginesDef", tEnginesDef)

  val tEnginesLanes = System.nanoTime()
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
          teamShape
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
          scene
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
  recordReader("enginesLanes", tEnginesLanes)

  val tEnginesTactical = System.nanoTime()
  val tacticalAnalyticsResult =
      if (GameCoexistenceGovernor.shouldRunAnalytics()) {
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
      } else {
          // Letter G: under RED pressure the non-critical analytics pass is
          // skipped; the default zero-confidence result keeps the downstream
          // behaviour/tactics chain (and the world-state store) well-formed.
          TacticalAnalyticsResult()
      }

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

      // Offside line (DecoyRun TRACE §1): VisionCore used to call analyze()
      // with NO lastDefenderX, so the stored result used the fixed 1200f
      // default and could not discriminate lanes. Pass the REAL measured
      // line — the deepest outfield opponent along the attack axis — so the
      // stored result discriminates for every consumer. analyze() assumes
      // attack +x, so a -x attack mirrors the lane geometry into a +x frame
      // (same transform as DecoyRunEngine.analyzeWithRealLine) and maps the
      // verdicts back onto the original lanes by receiver id. No opponents
      // or no confident attack direction → the default call (unchanged).
val offsideRiskEstimationResult = run {
    val attackSign = AttackAxisModel.directionIfConfident().toFloat()
    val opponents = scene.trackedPlayers.filter { p ->
        !p.isUserTeam && !p.isGoalkeeper &&
            p.lastSeenFrame == scene.frameNumber &&
            p.x.isFinite() && p.y.isFinite()
    }
    val lastDefender = opponents.maxByOrNull { it.x * attackSign }
    if (attackSign == 0f || lastDefender == null) {
        OffsideRiskEstimationEngine.analyze(passingGraph)
    } else if (attackSign > 0f) {
        OffsideRiskEstimationEngine.analyze(passingGraph, lastDefender.x)
    } else {
        val w = frame.width.toFloat()
        val mirrored = passingGraph.lanes.map { lane ->
            PassingLane(
                passer = lane.passer.copy(x = w - lane.passer.x, velocityX = -lane.passer.velocityX),
                receiver = lane.receiver.copy(x = w - lane.receiver.x, velocityX = -lane.receiver.velocityX),
                distance = lane.distance,
                pressure = lane.pressure,
                blocked = lane.blocked,
                score = lane.score
            )
        }
        val scored = OffsideRiskEstimationEngine.analyze(
            PassingLaneGraph(mirrored), w - lastDefender.x
        )
        val byReceiver = scored.lanes.associateBy { it.lane.receiver.id }
        OffsideRiskEstimationResult(
            passingGraph.lanes.mapNotNull { lane ->
                val verdict = byReceiver[lane.receiver.id] ?: return@mapNotNull null
                OffsideRisk(lane, verdict.risk, verdict.safe)
            }
        )
    }
}
recordReader("enginesTactical", tEnginesTactical)

    val tWorldStore = System.nanoTime()
    Phase3WorldStateStore.update(
        Phase3WorldState(
            ownership = ownership,
            possession = possession,
            defender = defender,
            defensiveStance = defensiveStance,
            // RING STATE -> DASH PROXY (#45): from the ring read above.
            isDashing = isDashing,
            // B5: the user line itself, as a store field (first publish).
            userLine = userLine,
            // B4: the low-block movement list (computed above) + its
            // engaged flag (queue item 9, L2).
            lowBlockMovements = lowBlockMovements,
            lowBlockActive = lowBlockResult.active,
            defenderHome = defenderHome,
            pressure = pressure,
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
            buildUpRecognitionResult = buildUpRecognitionResult,
            possessionStyleRecognitionResult = possessionStyleRecognitionResult,
            tacticalAnalyticsResult = tacticalAnalyticsResult,
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
    recordReader("worldStore", tWorldStore)

reportVisionLatency(processStartedNanos)
return state
    }

    // Phase 0 (all-round upgrade plan): per-reader timing. Records one
    // reader/engine-group span into VisionStageProfiler, whose
    // VISION_STAGE summary lines (every 30 s) are the existing output
    // path — no new logging system. Stage names carry the "reader:"
    // prefix so they can never collide with the OverlayService
    // coroutine stages (dedup, normalize, visionCore, assemble, ...).
    // Always on: there is no diagnostics flag in the repo, and the
    // cost is one nanoTime pair + one profiler record per group per
    // frame (~2-4 us total). Fail-open: a profiler throw can never
    // break a frame.
    private fun recordReader(stage: String, startNanos: Long) {
        val dtMs = (System.nanoTime() - startNanos) / 1_000_000L
        try { VisionStageProfiler.record("reader:$stage", dtMs) } catch (_: Throwable) { }
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

    /**
     * Reader-first goal detection, shared by the full-vision and
     * ball-only paths (single derivation site): the direct post-pair
     * reader measures the posts off this frame's pixels; the blob box
     * is the fallback. Fail-open: a null pair or a throw falls through
     * to GoalDetector.detect — never a guessed post. On the
     * CRITICAL-memory ball-only path the reader runs only in BALL_GOAL
     * mode, not on every ball-only frame.
     */
    private fun detectGoal(
        frame: FrameNormalizer.NormalizedFrame,
        filteredBlobs: List<ConnectedComponentEngine.Blob>
    ): GoalDetectionResult {
        try {
            PostPairReader.read(
                frame.buffer, frame.width, frame.height,
                frame.rowStride, frame.pixelStride
            )?.let { pair ->
                return GoalDetectionResult(
                    detected = true,
                    leftX = pair.left.centreX,
                    rightX = pair.right.centreX,
                    topY = minOf(pair.left.top, pair.right.top).toFloat(),
                    bottomY = maxOf(pair.left.bottom, pair.right.bottom).toFloat(),
                    confidence = pair.confidence,
                    source = GoalDetectionSource.READER,
                    barWidthLeftPx = pair.left.thickness,
                    barWidthRightPx = pair.right.thickness
                )
            }
        } catch (_: Throwable) {
        }
        return GoalDetector.detect(filteredBlobs)
    }

    private fun processBallOnly(
        filteredBlobs: List<ConnectedComponentEngine.Blob>,
        ball: BallDetectionResult,
        // Item 12 (C10): the frame's own size — the ball-only path used
        // the 1650x720 fallbacks, so the ball mode and the goal
        // calibration were computed against a frame that does not exist.
        frameWidth: Float,
        frameHeight: Float,
        // Post-pair reader (v3): the ball-only path had no frame buffer,
        // so it could only run the blob detect. The frame is threaded
        // through (single caller) so BALL_GOAL mode reads the posts too.
        frame: FrameNormalizer.NormalizedFrame
    ): GameStateSnapshot {
        val width = frameWidth
        val height = frameHeight
        val mode = BallOnlyDetector.modeFor(
            ball.x, width, AttackAxisModel.directionIfConfident()
        )
        val goal = if (mode == BallOnlyMode.BALL_GOAL) {
            val detected = detectGoal(frame, filteredBlobs)
            GoalPostCalibrationEngine.calibrate(detected, width, height)
            detected
        } else {
            // Queue item 13: the ball-only path never called calibrate()
            // outside BALL_GOAL mode, so latest() kept the previous
            // calibration indefinitely (unbounded, not the 500 ms coast).
            // Calibrate the not-detected result so the coast clock runs
            // honestly: within 500 ms the last valid is still coasted,
            // beyond it latest() goes invalid like the full-vision path.
            val notDetected = GoalDetectionResult(false, 0f, 0f, 0f, 0f, 0f)
            GoalPostCalibrationEngine.calibrate(notDetected, width, height)
            notDetected
        }
        val players = if (mode == BallOnlyMode.BALL_PLAYERS) {
            PlayerDetector.detect(filteredBlobs)
        } else {
            PlayerDetectionResult(false, 0f, emptyList())
        }
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
                    "BALL_ONLY mode=${mode} ball=(${String.format(Locale.US, "%.1f", ball.x)}," +
                        "${String.format(Locale.US, "%.1f", ball.y)}) goal=${mode == BallOnlyMode.BALL_GOAL && goal.detected} " +
                        "players=${if (mode == BallOnlyMode.BALL_PLAYERS) players.detections.size else 0}",
                    "VISION"
                )
            }
        } catch (_: Throwable) { }
        return state
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
}
