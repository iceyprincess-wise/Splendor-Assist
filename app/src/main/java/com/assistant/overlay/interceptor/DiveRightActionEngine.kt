package com.assistant.overlay.interceptor

object DiveRightActionEngine {

    fun vector(
        width: Float,
        height: Float
    ): FloatArray {

        return floatArrayOf(
            width * 0.50f,
            height * 0.72f,
            width * 0.82f,
            height * 0.38f
        )
    }
}
