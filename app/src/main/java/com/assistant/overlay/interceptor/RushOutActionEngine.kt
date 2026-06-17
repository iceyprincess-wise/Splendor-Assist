package com.assistant.overlay.interceptor

object RushOutActionEngine {

    fun vector(
        width: Float,
        height: Float
    ): FloatArray {

        return floatArrayOf(
            width * 0.50f,
            height * 0.82f,
            width * 0.50f,
            height * 0.20f
        )
    }
}
