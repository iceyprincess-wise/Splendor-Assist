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

        val leftBoundary  = pitchWidth * (0.15f + (Random.nextFloat() * 0.01f - 0.005f))
        val rightBoundary = pitchWidth * (0.85f + (Random.nextFloat() * 0.01f - 0.005f))

        val isLeftFlank  = wingerX < leftBoundary
        val isRightFlank = wingerX > rightBoundary

        // Not a wing situation: return null so WingBlockContributor skips.
        // Previously returned ball position (non-null), firing a redundant
        // central press that duplicated DefenseContributor with no added value.
        if (!isLeftFlank && !isRightFlank) return null

        val noiseX = Random.nextFloat() * 1.5f - 0.75f
        val noiseY = Random.nextFloat() * 1.5f - 0.75f

        val baseAnchorX = if (isLeftFlank) pitchWidth * 0.12f else pitchWidth * 0.88f
        val blockingAnchorX = (baseAnchorX + (wingerVx * 0.2f)) + noiseX
        val blockingAnchorY = wingerY + (wingerVy * 0.2f) + noiseY

        return WingBlockResult(blockingAnchorX, blockingAnchorY)
    }
}
