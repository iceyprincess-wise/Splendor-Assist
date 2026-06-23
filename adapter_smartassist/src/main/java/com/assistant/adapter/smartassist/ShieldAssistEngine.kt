package com.assistant.adapter.smartassist

object ShieldAssistEngine {

    fun shieldAngle(
        movementAngle:Float
    ):Float {

        return if (movementAngle >= 0f)
            movementAngle + 90f
        else
            movementAngle - 90f
    }

    fun shouldEngageShield(
        playerVelocity:Float,
        opponentDistance:Float
    ):Boolean {

        return playerVelocity > 0.15f &&
               opponentDistance < 220f
    }

    fun shieldHoldDuration():Long {
        return 45L
    }
}
