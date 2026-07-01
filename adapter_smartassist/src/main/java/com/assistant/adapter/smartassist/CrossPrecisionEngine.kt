package com.assistant.adapter.smartassist

data class CrossPrecisionResult(
    val crossX:Float,
    val crossY:Float,
    val confidence:Float
)

object CrossPrecisionEngine {

    fun calculate(
        x:Float,
        y:Float,
        strength:Int
    ):CrossPrecisionResult {

        val boost=(strength.coerceIn(0,100)/100f)

        return CrossPrecisionResult(
            crossX=x,
            crossY=y-(40f*boost),
            confidence=2.0f+(boost*6.0f)
        )
    }

    fun stunningCrossLeadDistance(
        strikerVelocity:Float
    ):Float {

        return strikerVelocity * 0.5f
    }

    fun stunningCrossSwipeDistance():Float {
        return 320f
    }

    fun stunningCrossDuration():Long {
        return 45L
    }
}
