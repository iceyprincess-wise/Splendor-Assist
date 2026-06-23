package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine

object FalseRunSequenceEngine {

    fun injectFalseRunSequence(
        inputEngine: LatencyDefeatingInputEngine,
        joystickX: Float,
        joystickY: Float,
        threatApproachVectorX: Float,
        threatApproachVectorY: Float
    ) {

        val escapeX = -threatApproachVectorY
        val escapeY = threatApproachVectorX

        inputEngine.injectZeroLatencySwipe(
            joystickX,
            joystickY,
            joystickX + (escapeX * 0.5f),
            joystickY + (escapeY * 0.5f),
            35L
        )

        inputEngine.injectZeroLatencySwipe(
            joystickX,
            joystickY,
            joystickX + escapeX,
            joystickY + escapeY,
            45L
        )
    }
}
