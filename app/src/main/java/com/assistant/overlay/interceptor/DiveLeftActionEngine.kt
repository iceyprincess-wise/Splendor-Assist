package com.assistant.overlay.interceptor

object DiveLeftActionEngine {

    fun vector(
        width: Float,
        height: Float
    ): FloatArray {

        return floatArrayOf(
            width * 0.50f,
            height * 0.72f,
            width * 0.18f,
            height * 0.38f
        )
    }
}
