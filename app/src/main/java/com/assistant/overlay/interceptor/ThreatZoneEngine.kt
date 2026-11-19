package com.assistant.overlay.interceptor

enum class ThreatZone {
    LEFT,
    RIGHT,
    CENTER,
    BOX,
    GOAL_AREA
}

object ThreatZoneEngine {

    /**
     * L12 (batch 1) — Model-X zone from BALL position (the proven model:
     * the goal sits at an X end of the frame). This is the
     * ThreatPriorityContributor.zoneOf() logic extracted verbatim so the
     * keeper scan can use it too: with a detected goal the ball inside
     * the goal span reads GOAL_AREA, within one band-width (0.12 of
     * width) of it reads BOX; without a goal the outer 12% / 24% of the
     * width read GOAL_AREA / BOX. Otherwise the ball's Y third reads
     * LEFT / RIGHT / CENTER. Unlike detect() (gauge-pixel input from
     * the scan strip), this takes the ball — so LEFT/RIGHT/CENTER and
     * the NEAR_POST/FAR_POST directions derived from them are reachable
     * from the save path.
     */
    fun zoneOfBall(
        ballX: Float,
        ballY: Float,
        goalLeftX: Float,
        goalRightX: Float,
        goalDetected: Boolean,
        width: Float,
        height: Float
    ): ThreatZone {
        if (width <= 0f || height <= 0f || !ballX.isFinite() || !ballY.isFinite())
            return ThreatZone.CENTER
        if (goalDetected && goalRightX > goalLeftX) {
            if (ballX >= goalLeftX && ballX <= goalRightX) return ThreatZone.GOAL_AREA
            val boxMargin = width * 0.12f
            if (ballX >= goalLeftX - boxMargin && ballX <= goalRightX + boxMargin) return ThreatZone.BOX
        } else {
            if (ballX > width * 0.88f || ballX < width * 0.12f) return ThreatZone.GOAL_AREA
            if (ballX > width * 0.76f || ballX < width * 0.24f) return ThreatZone.BOX
        }
        return when {
            ballY < height * 0.33f -> ThreatZone.LEFT
            ballY > height * 0.67f -> ThreatZone.RIGHT
            else -> ThreatZone.CENTER
        }
    }

    // No63-D22: from the only live caller — OmnipotentGoalkeeperEngine's
    // GK scan strip, which only ever feeds gauge pixels in the bottom
    // 25% of the frame (y > 0.75h) — detect() can only return BOX
    // (0.65h < y <= 0.85h) or GOAL_AREA (y > 0.85h). The LEFT/RIGHT/
    // CENTER arms are unreachable from the live path, NOT dead code: the
    // function is correct for general input and the arms are the
    // documented behaviour for any future caller that feeds full-frame
    // coordinates. Kept as-is; the reachability is recorded here and as
    // queue item Q63-4. (Related: from the scan strip the NativePorts
    // zone bonus is always BOX=10 or GOAL_AREA=0 — see Q63-3.)
    fun detect(
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): ThreatZone {

        // No63-D23: with width/height <= 0 the thresholds are 0 or
        // negative, so any non-origin point fell through to GOAL_AREA —
        // the most dangerous zone, from a degenerate frame (only the
        // origin itself fell to CENTER, by accident of the comparisons).
        // A degenerate frame now returns CENTER (no claim about a zone)
        // instead.
        if (width <= 0 || height <= 0)
            return ThreatZone.CENTER

        return when {

            y > (height * 0.85f) ->
                ThreatZone.GOAL_AREA

            y > (height * 0.65f) ->
                ThreatZone.BOX

            x < width / 3 ->
                ThreatZone.LEFT

            x > (width * 2 / 3) ->
                ThreatZone.RIGHT

            else ->
                ThreatZone.CENTER
        }
    }
}
