package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine

class ReboundCollector {

    private var shotFiredTimestamp = 0L

    fun monitorReboundContext(
        isGkInParryAnimation:Boolean,
        closestAttackerX:Float,
        closestAttackerY:Float,
        shootButtonX:Float,
        shootButtonY:Float,
        inputEngine:LatencyDefeatingInputEngine
    ){

        val currentTime =
            System.currentTimeMillis()

        val reboundDistance = kotlin.math.hypot(closestAttackerX.toDouble(), closestAttackerY.toDouble())

        if(
            isGkInParryAnimation &&
            currentTime - shotFiredTimestamp <= 1500L &&
            reboundDistance < 250.0
        ){
            inputEngine.injectZeroLatencySwipe(
                shootButtonX,
                shootButtonY,
                shootButtonX,
                shootButtonY,
                30L
            )
        }
    }

    fun logActiveShot(){
        shotFiredTimestamp =
            System.currentTimeMillis()
    }
}
