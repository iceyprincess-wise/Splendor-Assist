package com.assistant

/** Preserve jersey chroma that a luminance-only foreground gate discards. */
object ForegroundPixel {
    fun kind(r: Int, g: Int, b: Int): Int = when {
        r > 140 && r > g+50 && r > b+50 -> 2
        b > 140 && b > r+50 && b > g+50 -> 3
        g > 160 && g > r+60 && g > b+60 -> 4
        else -> 1
    }
    fun retained(r: Int, g: Int, b: Int, threshold: Int): Boolean =
        kind(r,g,b) != 1 || ((r*13933+g*46871+b*4732) shr 16) >= threshold
}
