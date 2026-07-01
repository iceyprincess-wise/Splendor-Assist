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
        PressureFieldResult(0,0, emptyArray())
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
