package com.assistant.adapter.smartassist

data class DefensiveContainmentResult(
    val movements: List<Pair<Float,Float>>
)

object LowBlockContainmentEngine {

    fun applyLowBlockContainment(
        ballX:Float,
        ballY:Float,
        pitchWidth:Float,
        pitchHeight:Float,
        defenders:List<Pair<Float,Float>>
    ):DefensiveContainmentResult {

        val updated =
            mutableListOf<Pair<Float,Float>>()

        val targetLineY =
            pitchHeight * 0.85f

        if (ballY >= pitchHeight * 0.70f) {

            defenders.forEachIndexed { index, defender ->

                val optimalX =
                    (pitchWidth / (defenders.size + 1)) *
                    (index + 1)

                val horizontalBias =
                    ((ballX / pitchWidth) - 0.5f) *
                    (pitchWidth * 0.18f)

                val targetX =
                    (
                        defender.first +
                        ((optimalX - defender.first) * 0.4f) +
                        horizontalBias
                    ).coerceIn(0f, pitchWidth)

                updated.add(
                    Pair(
                        targetX,
                        targetLineY
                    )
                )
            }
        }

        return DefensiveContainmentResult(
            movements = updated
        )
    }
}
