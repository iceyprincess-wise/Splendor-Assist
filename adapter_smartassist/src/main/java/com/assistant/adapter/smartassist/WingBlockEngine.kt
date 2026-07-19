package com.assistant.adapter.smartassist

import kotlin.random.Random

data class WingBlockResult(
    val targetX: Float,
    val targetY: Float
)

object WingBlockEngine {

    fun calculateWingBlockVector(
        wingerX: Float,
        wingerY: Float,
        wingerVx: Float,
        wingerVy: Float,
        pitchWidth: Float
    ): WingBlockResult? {

        // Fuzz flank check parameters subtly per evaluation to break static grid telemetry
        val leftBoundaryFuzz = pitchWidth * (0.15f + (Random.nextFloat() * 0.01f - 0.005f))
        val rightBoundaryFuzz = pitchWidth * (0.85f + (Random.nextFloat() * 0.01f - 0.005f))

        val isLeftFlank = wingerX < leftBoundaryFuzz
        val isRightFlank = wingerX > rightBoundaryFuzz

        // Sub-pixel position noise to ensure resulting coordinate streams look human-driven
        val trackingNoiseX = Random.nextFloat() * 1.5f - 0.75f // +/- 0.75 units coordinate wobble
        val trackingNoiseY = Random.nextFloat() * 1.5f - 0.75f

        if (isLeftFlank || isRightFlank) {
            val baseAnchorX = if (isLeftFlank) pitchWidth * 0.12f else pitchWidth * 0.88f
            val blockingAnchorX = (baseAnchorX + (wingerVx * 0.2f)) + trackingNoiseX
            val blockingAnchorY = wingerY + (wingerVy * 0.2f) + trackingNoiseY

            return WingBlockResult(
                blockingAnchorX,
                blockingAnchorY
            )
        }

        return WingBlockResult(
            wingerX + trackingNoiseX,
            wingerY + trackingNoiseY
        )
    }
}
