package com.assistant.overlay.interceptor

enum class HeightBand { TOP, MID, BOTTOM }

data class ThreatDecision(
    val threat: ThreatType,
    val zone: ThreatZone,
    val direction: ShotDirection,
    val priority: Int,
    val heightBand: HeightBand = HeightBand.MID,
    val normX: Float = 0.5f
)

// No64-D9: the single PANIC line (150) shared by the interceptor engines.
// ShotAnticipationEngine, PanicSaveEngine and OneVsOnePanicEngine all read
// this const. NativePorts.panic keeps its own literal (see the note there —
// NativePorts.kt cannot reference this const without importing this package,
// which already imports NativePorts), and the two `/ 150f` authority
// divisors in ThreatPriorityContributor / PanicSaveContributor are
// intentionally unchanged.
const val PANIC_PRIORITY_THRESHOLD = 150

object ThreatPriorityEngine {

    // No63-D25: the coordinate parameters used to default to x=0, y=0,
    // width=1, height=1 — a caller that forgot coordinates silently
    // evaluated a degenerate (0,0,1,1) threat (TOP band, normX=0) instead
    // of failing to compile. Both live callers pass all six arguments
    // (ThreatPriorityContributor, OmnipotentGoalkeeperEngine), so the
    // defaults are removed: coordinates are now required.
    fun evaluate(
        threat: ThreatType,
        zone: ThreatZone,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        // Q68-86 (batch 5b — D15): the live ball speed (px/frame) from
        // BallTrajectoryPredictor, forwarded to ShotDirectionEngine so
        // LONG_BALL is trajectory-gated. Defaults to 0 (no trajectory)
        // so a caller without trajectory data gets CENTER, never a
        // long-ball claim it cannot support.
        ballSpeedPxPerFrame: Float = 0f,
        // Item 12 (A4): predicted ball point (capture px) when a valid
        // trajectory prediction exists. normX/heightBand then derive
        // from the predicted impact point, not the current ball pixel,
        // so a laterally moving shot is dived at where it will cross
        // the line. Defaults preserve current-position behaviour for
        // callers without a prediction.
        predictedBallX: Float = 0f,
        predictedBallY: Float = 0f,
        hasBallPrediction: Boolean = false
    ): ThreatDecision {
        val direction = ShotDirectionEngine.detect(zone, threat, ballSpeedPxPerFrame)

        // No63-D24: the native call used to write into a shared
        // `outBuffer` field on this singleton. Both live callers run on
        // the capture-loop thread sequentially in practice, so no live
        // race was observed — but the object carried unsynchronised shared
        // mutable state for no reason. A per-call local buffer is free
        // and provably race-free.
        val outBuffer = FloatArray(3)

        // Item 12 (A4): aim at the predicted point when one exists —
        // priority is untouched (threat() does not use x/y for it).
        val evalX = if (hasBallPrediction) predictedBallX.toInt() else x
        val evalY = if (hasBallPrediction) predictedBallY.toInt() else y

        // Kotlin port (NativePorts.threat): no C threat function exists in app/src/main/cpp.
        com.assistant.NativePorts.threat(
            threat.score, zone.ordinal, direction.ordinal,
            InterceptionRuntimeRegistry.awareness, InterceptionRuntimeRegistry.prediction,
            GoalkeeperAdaptiveFeedbackEngine.interceptionBonus(),
            GoalkeeperAdaptiveFeedbackEngine.recoveryBonus(),
            evalX, evalY, width, height, outBuffer
        )

        val priority = outBuffer[0].toInt()
        val heightBand = when (outBuffer[1].toInt()) {
            0 -> HeightBand.TOP
            2 -> HeightBand.BOTTOM
            else -> HeightBand.MID
        }
        val normX = outBuffer[2]

        return ThreatDecision(
            threat = threat,
            zone = zone,
            direction = direction,
            priority = priority,
            heightBand = heightBand,
            normX = normX
        )
    }
}
