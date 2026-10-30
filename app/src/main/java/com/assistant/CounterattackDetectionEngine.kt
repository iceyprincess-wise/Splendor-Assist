package com.assistant

object CounterattackDetectionEngine{
    private val outBuffer = FloatArray(2)
    fun analyze(scene:SceneSnapshot, teamShape:TeamShapeResult, offensiveLine:OffensiveLineResult):CounterattackDetectionResult{
        val attackers = scene.trackedPlayers.count{ it.isUserTeam }
        CounterattackPort.analyze(attackers, teamShape.found, teamShape.confidence, offensiveLine.found, offensiveLine.confidence, outBuffer)
        return CounterattackDetectionResult(detected = outBuffer[0] > 0.5f, confidence = outBuffer[1])
    }
}
/* ======
CounterattackDetectionEngine Anchor
====== */
