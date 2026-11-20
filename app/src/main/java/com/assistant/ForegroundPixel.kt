package com.assistant

/** Preserve jersey chroma that a luminance-only foreground gate discards. */
object ForegroundPixel {
    // 6a: unified with JerseyColorSegmentation.classify() — this was an
    // identical copy of the same three rules (red->USER, blue->OPPONENT,
    // green->GOALKEEPER) and drifted with it. One rule set now: yellow /
    // light-blue -> USER (Ed's 1st/3rd kits), green -> GOALKEEPER,
    // saturated blue -> OPPONENT, red/black -> background (1). See
    // classify()'s KDoc for the measured ground truth.
    // Kinds: 1 = background/other, 2 = USER, 3 = OPPONENT, 4 = GOALKEEPER.
    fun kind(r: Int, g: Int, b: Int): Int = when (JerseyColorSegmentation.classify(r.toFloat(), g.toFloat(), b.toFloat()).team) {
        JerseyColorSegmentation.Team.USER -> 2
        JerseyColorSegmentation.Team.OPPONENT -> 3
        JerseyColorSegmentation.Team.GOALKEEPER -> 4
        JerseyColorSegmentation.Team.UNKNOWN -> 1
    }
    fun retained(r: Int, g: Int, b: Int, threshold: Int): Boolean =
        kind(r,g,b) != 1 || ((r*13933+g*46871+b*4732) shr 16) >= threshold
}
