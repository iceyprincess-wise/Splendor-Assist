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

object ThreatPriorityEngine {
    private val outBuffer = FloatArray(3)

    fun evaluate(
        threat: ThreatType,
        zone: ThreatZone,
        x: Int = 0,
        y: Int = 0,
        width: Int = 1,
        height: Int = 1
    ): ThreatDecision {
        val direction = ShotDirectionEngine.detect(zone, threat)
        
        // Delegate heavy compute to bare-metal C standard
        com.assistant.NativeBridge.nativeThreatPriorityCompute(
            threat.score, zone.ordinal, direction.ordinal,
            InterceptionRuntimeRegistry.awareness, InterceptionRuntimeRegistry.prediction,
            GoalkeeperAdaptiveFeedbackEngine.interceptionBonus(),
            GoalkeeperAdaptiveFeedbackEngine.recoveryBonus(),
            x, y, width, height, outBuffer
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
