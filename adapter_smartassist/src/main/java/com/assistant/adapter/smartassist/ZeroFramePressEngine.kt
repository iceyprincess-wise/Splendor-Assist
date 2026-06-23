package com.assistant.adapter.smartassist

import kotlin.math.hypot
import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine

class ZeroFramePressEngine(
    private val inputEngine:LatencyDefeatingInputEngine
){

    private val stateBuffer =
        FloatArray(4)

    fun executeInstantSteal(
        defX:Float,
        defY:Float,
        ballX:Float,
        ballY:Float,
        dashButtonX:Float,
        dashButtonY:Float
    ){

        stateBuffer[0]=defX
        stateBuffer[1]=defY
        stateBuffer[2]=ballX
        stateBuffer[3]=ballY

        val distanceToBall =
            hypot(
                (stateBuffer[2]-stateBuffer[0]).toDouble(),
                (stateBuffer[3]-stateBuffer[1]).toDouble()
            )

        if(distanceToBall < 45.0){

            inputEngine.injectZeroLatencySwipe(
                dashButtonX,
                dashButtonY,
                dashButtonX + 5f,
                dashButtonY,
                25L
            )
        }
    }
}
