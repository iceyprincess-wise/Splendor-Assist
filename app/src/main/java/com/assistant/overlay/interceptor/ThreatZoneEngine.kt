package com.assistant.overlay.interceptor

enum class ThreatZone {
    LEFT,
    RIGHT,
    CENTER,
    BOX,
    GOAL_AREA
}

object ThreatZoneEngine {

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
