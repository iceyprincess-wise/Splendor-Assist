package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























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

/* ========
FastBreakDetectionEngine
======== */
