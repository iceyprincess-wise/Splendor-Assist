package com.assistant.overlay.interceptor

/**
 * Exact enum signature preserved to fix cross-file compilation dependencies.
 */
enum class ThreatType(
    val score: Int
) {
    PURPLE(100),
    GREEN(90),
    RED(85),
    ORANGE(75),
    YELLOW(60),
    WHITE(45),
    NONE(0)
}

/**
 * High-performance, zero-allocation threat evaluation engine for eFootball.
 * Preserves the original public API contract to guarantee compilation success.
 */
object ThreatClassifierEngine {

    /**
     * Preserves public method signature while migrating internal evaluation
     * to zero-allocation local ratio balancing to handle shadow casting.
     */
    fun classify(
        r: Int,
        g: Int,
        b: Int
    ): ThreatType {
        val total = r + g + b
        if (total == 0) return ThreatType.NONE

        // White detection (High brightness balance across channels)
        if (r > 190 && g > 190 && b > 190) return ThreatType.WHITE

        // Performance ratio scaling
        val rRatio = (r * 100) / total
        val gRatio = (g * 100) / total
        val bRatio = (b * 100) / total

        return when {
            // PURPLE: Dynamic player runs or extreme manual match threats
            r > 120 && b > 120 && g < 100 -> ThreatType.PURPLE

            // GREEN: Open trajectory windows / Safe pitch tracking
            gRatio > 42 && rRatio < 38 -> ThreatType.GREEN

            // RED: Opposition physical presence pressing
            rRatio > 50 && gRatio < 30 && bRatio < 30 -> ThreatType.RED

            // ORANGE: Box vulnerability penetration
            rRatio > 45 && gRatio > 30 && bRatio < 25 -> ThreatType.ORANGE

            // YELLOW: Passing lane or mid-tier tactical adjustments
            rRatio > 40 && gRatio > 40 && bRatio < 25 -> ThreatType.YELLOW

            else -> ThreatType.NONE
        }
    }
}
