package com.assistant.adapter.smartassist

data class Phase3WorldState(
    val closestPlayer: ClosestPlayerResult = ClosestPlayerResult(false),
    val ownership: BallOwnershipResult = BallOwnershipResult(false),
    val possession: BallPossessionResult = BallPossessionResult(false),
    val attacker: ActiveAttackerResult = ActiveAttackerResult(false),
    val defender: ActiveDefenderResult = ActiveDefenderResult(false),
    val formation: FormationResult = FormationResult(false),
    val teamShape: TeamShapeResult = TeamShapeResult(false),
    val defensiveLine: DefensiveLineResult = DefensiveLineResult(false),
    val offensiveLine: OffensiveLineResult = OffensiveLineResult(false),
    val occupancy: SpaceOccupancyResult =
        SpaceOccupancyResult(0,0, emptyArray()),
    val pressure: PressureFieldResult =
        PressureFieldResult(0,0, emptyArray()),
    val tacticalMapResult: TacticalMapResult =
        TacticalMapResult(),

    val defensiveCompactnessResult: DefensiveCompactnessResult =
        DefensiveCompactnessResult(),

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
        TacticalIntelligenceResult()
)

object Phase3WorldStateStore {

    @Volatile
    private var latest = Phase3WorldState()

    fun update(state: Phase3WorldState) {
        latest = state
    }

    fun current(): Phase3WorldState =
        latest
}
