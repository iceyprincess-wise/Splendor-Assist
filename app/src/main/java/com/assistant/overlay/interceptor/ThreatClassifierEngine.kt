package com.assistant.overlay.interceptor

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

object ThreatClassifierEngine {

    fun classify(
        r: Int,
        g: Int,
        b: Int
    ): ThreatType {

        return when {

            r > 130 && b > 130 && g < 90 ->
                ThreatType.PURPLE

            g > 170 && r < 160 ->
                ThreatType.GREEN

            r > 180 && g < 100 && b < 100 ->
                ThreatType.RED

            r > 180 && g > 120 && b < 120 ->
                ThreatType.ORANGE

            r > 170 && g > 170 && b < 120 ->
                ThreatType.YELLOW

            r > 190 && g > 190 && b > 190 ->
                ThreatType.WHITE

            else ->
                ThreatType.NONE
        }
    }
}
