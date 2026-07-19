package com.assistant.overlay.interceptor

import kotlin.random.Random

object BlockRightActionEngine {

    fun vector(
        width: Float,
        height: Float
    ): FloatArray {
        // Introduce micro-scale coordinate fuzzing to break up rigid, identical screen coordinate signatures
        val jitterX1 = Random.nextFloat() * 0.004f - 0.002f
        val jitterY1 = Random.nextFloat() * 0.004f - 0.002f
        val jitterX2 = Random.nextFloat() * 0.006f - 0.003f
        val jitterY2 = Random.nextFloat() * 0.006f - 0.003f

        return floatArrayOf(
            width * (0.50f + jitterX1),
            height * (0.72f + jitterY1),
            width * (0.70f + jitterX2),
            height * (0.55f + jitterY2)
        )
    }
}
