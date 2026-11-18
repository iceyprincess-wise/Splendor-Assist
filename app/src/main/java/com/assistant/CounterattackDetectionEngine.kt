package com.assistant

object CounterattackDetectionEngine{
    fun analyze(scene:SceneSnapshot, teamShape:TeamShapeResult, offensiveLine:OffensiveLineResult):CounterattackDetectionResult{
        // No68-G-D14: count fresh tracks only. The old code counted raw
        // scene.trackedPlayers, so stale/ghost tracks inflated the
        // headcount (the same zombie-track class VisionTrust caps in
        // FrameAssembler). Sibling detectors use ObservedTactics.players.
        // No68-G-D15: count runners ahead of the ball in the known attack
        // direction, not every user-team player on the pitch (the old
        // count included defenders in our own half). When the direction
        // is unknown (AttackAxisModel 0) or the ball is not visible, fall
        // back to the full fresh count, preserving old behaviour.
        val fresh = ObservedTactics.players(scene)
        val direction = AttackAxisModel.directionIfConfident()
        val attackers = if (direction == 0 || !scene.trackedBallVisible) {
            fresh.count { it.isUserTeam }
        } else if (direction > 0) {
            fresh.count { it.isUserTeam && it.x > scene.trackedBallX }
        } else {
            fresh.count { it.isUserTeam && it.x < scene.trackedBallX }
        }
        // No68-G-D16: per-call local buffer. The old shared outBuffer on
        // this singleton is the same fragility no63-D24 removed from
        // ThreatPriorityEngine (unsynchronised shared mutable state).
        val outBuffer = FloatArray(2)
        CounterattackPort.analyze(attackers, teamShape.found, teamShape.confidence, offensiveLine.found, offensiveLine.confidence, outBuffer)
        // No68-G-D17: detect from the same raw confidence that is
        // reported. The old code thresholded the smoothstep-amplified
        // out[0] while reporting raw out[1] — two scales for one event
        // (same 0.5 cut by monotonicity, but the pair disagreed).
        return CounterattackDetectionResult(detected = outBuffer[1] > 0.5f, confidence = outBuffer[1])
    }
}
