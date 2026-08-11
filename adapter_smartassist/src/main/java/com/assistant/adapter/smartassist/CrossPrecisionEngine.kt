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
            confidence=(0.60f+(boost*0.40f)).coerceIn(0f,1f)
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
