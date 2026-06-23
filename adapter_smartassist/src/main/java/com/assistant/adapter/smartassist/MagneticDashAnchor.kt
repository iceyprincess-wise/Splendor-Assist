package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine

class MagneticDashAnchor(
    private val inputEngine:LatencyDefeatingInputEngine
){

    private var lastPulseTime = 0L

    fun processHighSpeedDribble(
        dashX:Float,
        dashY:Float,
        directionalX:Float,
        directionalY:Float
    ){

        val currentTime =
            System.nanoTime()

        if(
            currentTime - lastPulseTime >
            60_000_000L
        ){

            inputEngine.injectZeroLatencySwipe(
                dashX,
                dashY,
                directionalX,
                directionalY,
                30L
            )

            lastPulseTime =
                currentTime
        }
    }
}
