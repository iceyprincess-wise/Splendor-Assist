object WingBlockEngine {

    fun calculateWingBlockVector(
        wingerX: Float,
        wingerY: Float,
        wingerVx: Float,
        wingerVy: Float,
        pitchWidth: Float
    ): WingBlockResult? {

        val leftBoundary  = pitchWidth * 0.15f
        val rightBoundary = pitchWidth * 0.85f

        val isLeftFlank  = wingerX < leftBoundary
        val isRightFlank = wingerX > rightBoundary

        // Not a wing situation: return null so WingBlockContributor skips.
        // Previously returned ball position (non-null), firing a redundant
        // central press that duplicated DefenseContributor with no added value.
        if (!isLeftFlank && !isRightFlank) return null

        val baseAnchorX = if (isLeftFlank) pitchWidth * 0.12f else pitchWidth * 0.88f
        val blockingAnchorX = (baseAnchorX + (wingerVx * 0.2f))
        val blockingAnchorY = wingerY + (wingerVy * 0.2f)

        return WingBlockResult(blockingAnchorX, blockingAnchorY)
    }
}
/* ======
WingBlockEngine Anchor
====== */

/* ========
WingOverloadDetectionResult
======== */
