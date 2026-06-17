package com.assistant.overlay.interceptor

object HoldPositionActionEngine {

    fun vector(
        width: Float,
        height: Float
    ): FloatArray {

        return floatArrayOf(
            width * 0.50f,
            height * 0.72f,
            width * 0.50f,
            height * 0.72f
        )
    }
}
