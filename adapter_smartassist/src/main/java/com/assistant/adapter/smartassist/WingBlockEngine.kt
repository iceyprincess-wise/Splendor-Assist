package com.assistant.adapter.smartassist

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

        val isLeftFlank =
            wingerX < pitchWidth * 0.15f

        val isRightFlank =
            wingerX > pitchWidth * 0.85f

        if (isLeftFlank || isRightFlank) {

            val blockingAnchorX = ((if (isLeftFlank) pitchWidth * 0.12f else pitchWidth * 0.88f) + (wingerVx * 0.2f))

            val blockingAnchorY =
                wingerY + (wingerVy * 0.2f)

            return WingBlockResult(
                blockingAnchorX,
                blockingAnchorY
            )
        }

        return null
    }
}
