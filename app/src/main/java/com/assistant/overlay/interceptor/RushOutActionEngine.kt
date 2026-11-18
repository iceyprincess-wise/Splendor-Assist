package com.assistant.overlay.interceptor

object RushOutActionEngine {

    private fun biasMultiplier(): Float {

        return when (
            GoalkeeperBiasRegistry.currentBias
        ) {

            // No64-D10: Symmetric by design: shading either side starts the rush from the same depth.
            KeeperBias.SHADE_LEFT, KeeperBias.SHADE_RIGHT ->
                1.20f

            KeeperBias.TIGHTEN_GOAL_AREA ->
                1.30f

            // No64-D11: PROTECT_* and HOLD_CENTER do not scale the rush-out start.
            // No65-D11: the `else` arm is gone — the when covers all six
            // KeeperBias values, so the else was redundant (compiler
            // warning) and duplicated the 1.00f above. A future KeeperBias
            // entry now fails compilation until it is given an arm.
            KeeperBias.PROTECT_NEAR_POST, KeeperBias.PROTECT_FAR_POST, KeeperBias.HOLD_CENTER ->
                1.00f
        }
    }

    fun vector(
        width: Float,
        height: Float
    ): FloatArray {

        val safeWidth = width.coerceAtLeast(0f)
        val safeHeight = height.coerceAtLeast(0f)

        val bias =
            biasMultiplier()

        val adaptive =
            GoalkeeperAdaptiveFeedbackEngine
                .rushOutMultiplier()

        val startX =
            safeWidth * 0.50f

        val startY =
            (safeHeight * 0.82f * bias * adaptive)
                .coerceIn(0f, safeHeight)

        val endX =
            safeWidth * 0.50f

        val endY =
            (safeHeight * 0.20f)
                .coerceIn(0f, safeHeight)

        return floatArrayOf(
            startX,
            startY,
            endX,
            endY
        )
    }
}
