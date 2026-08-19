package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class FastBreakDetectionResult(
    val detected:Boolean=false,
    val speed:Float=0f,
    val confidence:Float=0f
)

object FastBreakDetectionEngine{

    fun analyze(
        scene:SceneSnapshot
    ):FastBreakDetectionResult{

        var speed=0f

        scene.trackedPlayers
            .filter{it.isUserTeam}
            .forEach{

                speed=maxOf(
                    speed,
                    hypot(
                        it.velocityX.toDouble(),
                        it.velocityY.toDouble()
                    ).toFloat()
                )
            }

        val confidence=
            (speed/180f)
                .coerceIn(0f,1f)

        return FastBreakDetectionResult(
            detected=confidence>=0.55f,
            speed=speed,
            confidence=confidence
        )
    }
}
