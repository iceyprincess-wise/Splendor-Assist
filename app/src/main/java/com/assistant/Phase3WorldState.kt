package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class Phase3WorldState(
    val closestPlayer: ClosestPlayerResult = ClosestPlayerResult(false),
    val ownership: BallOwnershipResult = BallOwnershipResult(false),
    val possession: BallPossessionResult = BallPossessionResult(false),
    // Letter A item 9: the stored `attacker` copy is deleted — written
    // by VisionCore but read by nothing. The live attacker flows
    // VisionCore → ActiveDefenderEngine.compute() directly (local
    // `attacker` / `defensiveAttacker` above), never through this store;
    // FrameAssembler never read world.attacker either.
    val defender: ActiveDefenderResult = ActiveDefenderResult(false),
    val formation: FormationResult = FormationResult(false),
    val teamShape: TeamShapeResult = TeamShapeResult(false),
    val defensiveLine: DefensiveLineResult = DefensiveLineResult(false),
    // L13 (batch 1): the tight defensive stance (DefensiveStanceProducer),
    // computed in VisionCore from computeUserLine. Read by FrameAssembler.
    val defensiveStance: DefensiveStance = DefensiveStance(false),
    // B5 (batch 2): the raw user line itself (DefensiveLineEngine.
    // computeUserLine), published as a store field by VisionCore — the
    // stance above is derived from it. Read by FrameAssembler (frame
    // userLineX/Spread/Found). userLine.found=false = no line this frame.
    val userLine: DefensiveLineResult = DefensiveLineResult(false),
    // B4 (batch 2): the low-block movement list, one target per defender
    // (by defender id), computed in VisionCore from
    // LowBlockContainmentEngine. Read by FrameAssembler. The movements
    // are published even while the block is inactive (hold positions);
    // lowBlockActive (queue item 9, L2) is the engine's own engaged
    // flag — the frame's lowBlockActive gate reads this, not the list.
    val lowBlockMovements: List<com.assistant.runtime.LowBlockMovement> = emptyList(),
    val lowBlockActive: Boolean = false,
    // L14 (batch 1): the tracked defender's home on the tight line
    // (HomePositionProducer), computed in VisionCore. Read by
    // FrameAssembler; DefenseContributor uses it as the stopper anchor.
    val defenderHome: HomePosition = HomePosition(false),
    val offensiveLine: OffensiveLineResult = OffensiveLineResult(false),
    val occupancy: SpaceOccupancyResult =
        SpaceOccupancyResult(0,0, emptyArray()),
    val pressure: PressureFieldResult =
        PressureFieldResult(0,0, emptyArray()),
    val tacticalMapResult: TacticalMapResult =
        TacticalMapResult(),

    val defensiveCompactnessResult: DefensiveCompactnessResult =
        DefensiveCompactnessResult(),

    // Midfield (queue item 5, batch midfield): the measured midfield
    // line + its compactness over the band players only. Read by
    // FrameAssembler (frame midfieldLineX/Spread/Found/Compactness/
    // Confidence); the compactness is NOT fed to pressing/analytics.
    val midfieldLine: MidfieldLineResult = MidfieldLineResult(false),
    val midfieldCompactnessResult: MidfieldCompactnessResult =
        MidfieldCompactnessResult(),

    val wingOverloadDetectionResult: WingOverloadDetectionResult =
        WingOverloadDetectionResult(),

    val centralOverloadDetectionResult: CentralOverloadDetectionResult =
        CentralOverloadDetectionResult(),

    val passingGraph: PassingLaneGraph =
        PassingLaneGraph(),

    val throughBallAnalysis: ThroughBallLaneAnalysis =
        ThroughBallLaneAnalysis(),

    val crossingLaneAnalysis: CrossingLaneAnalysis =
        CrossingLaneAnalysis(),

    val shootingLaneAnalysis: ShootingLaneAnalysis =
        ShootingLaneAnalysis(),

    val blockedLanePredictionAnalysis: BlockedLanePredictionAnalysis =
        BlockedLanePredictionAnalysis(),

    val defenderInterceptionPredictionAnalysis: DefenderInterceptionPredictionAnalysis =
        DefenderInterceptionPredictionAnalysis(),

    val openSpaceDetectionResult: OpenSpaceDetectionResult =
        OpenSpaceDetectionResult(),

    val receiverRankingResult: ReceiverRankingResult =
        ReceiverRankingResult(),

    val runPredictionResult: RunPredictionResult =
        RunPredictionResult(),

    val overlapDetectionResult: OverlapDetectionResult =
        OverlapDetectionResult(),

    val counterattackDetectionResult: CounterattackDetectionResult =
        CounterattackDetectionResult(),

    val fastBreakDetectionResult: FastBreakDetectionResult =
        FastBreakDetectionResult(),

    val offsideRiskEstimationResult: OffsideRiskEstimationResult =
        OffsideRiskEstimationResult(),

    val pressingRecognitionResult: PressingRecognitionResult =
        PressingRecognitionResult(),

    val counterPressRecognitionResult: CounterPressRecognitionResult =
        CounterPressRecognitionResult(),

    val buildUpRecognitionResult: BuildUpRecognitionResult =
        BuildUpRecognitionResult(),

    val possessionStyleRecognitionResult: PossessionStyleRecognitionResult =
        PossessionStyleRecognitionResult(),

    val tacticalAnalyticsResult: TacticalAnalyticsResult =
        TacticalAnalyticsResult(),

    val tacticalBehaviorRecognitionResult: TacticalBehaviorRecognitionResult =
        TacticalBehaviorRecognitionResult(),

    val tacticalIntelligenceResult: TacticalIntelligenceResult =
        TacticalIntelligenceResult(),

    val opponentBehaviourLearningResult: OpponentBehaviourLearningResult =
        OpponentBehaviourLearningResult(),

    val playerTendencyLearningResult: PlayerTendencyLearningResult =
        PlayerTendencyLearningResult(),

    val preferredPassingLaneLearningResult: PreferredPassingLaneLearningResult =
        PreferredPassingLaneLearningResult(),

    val shootingHabitLearningResult: ShootingHabitLearningResult =
        ShootingHabitLearningResult(),

    val formationAdaptationResult: FormationAdaptationResult =
        FormationAdaptationResult(),

    val runtimeConfidenceCalibrationResult: RuntimeConfidenceCalibrationResult =
        RuntimeConfidenceCalibrationResult(),

    val onlineParameterAdaptationResult: OnlineParameterAdaptationResult =
        OnlineParameterAdaptationResult(),

    val temporalMemoryState: TemporalMemoryState =
        TemporalMemoryEngine.initialize()
)

