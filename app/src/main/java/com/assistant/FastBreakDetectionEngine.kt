package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















object FastBreakDetectionEngine{

    fun analyze(
        scene:SceneSnapshot
    ):FastBreakDetectionResult{

        var speed=0f

        for (i in scene.trackedPlayers.indices) {
            val player = scene.trackedPlayers[i]
            if (!player.isUserTeam) continue
            speed = maxOf(speed, hypot(player.velocityX.toDouble(), player.velocityY.toDouble()).toFloat())
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

/* ========
FieldLineDetectionResult
======== */
