package com.assistant.overlay.interceptor

object RushOutActionEngine {

    private fun biasMultiplier(): Float {

        return when (
            GoalkeeperBiasRegistry.currentBias
        ) {

            KeeperBias.SHADE_LEFT ->
                1.20f

            KeeperBias.SHADE_RIGHT ->
                1.20f

            KeeperBias.TIGHTEN_GOAL_AREA ->
                1.30f

            else ->
                1.00f
        }
    }


    fun vector(
        width: Float,
        height: Float
    ): FloatArray {

        val bias =
            biasMultiplier()

        val adaptive =
            GoalkeeperAdaptiveFeedbackEngine
                .rushOutMultiplier()

        return floatArrayOf(
            width * 0.50f,
            height * 0.82f * bias * adaptive,
            width * 0.50f,
            height * 0.20f
        )
    }
}
