package com.assistant.adapter.smartassist

object JerseyColorSegmentation {

    enum class Team {
        USER,
        OPPONENT,
        GOALKEEPER,
        UNKNOWN
    }

    data class ClassificationResult(
        val team: Team,
        val confidence: Float
    )

    fun classify(
        red: Float,
        green: Float,
        blue: Float
    ): ClassificationResult {

        return when {

            red > green + 30f &&
            red > blue + 30f ->
                ClassificationResult(
                    Team.USER,
                    1f
                )

            blue > red + 30f &&
            blue > green + 30f ->
                ClassificationResult(
                    Team.OPPONENT,
                    1f
                )

            green > red + 30f &&
            green > blue + 30f ->
                ClassificationResult(
                    Team.GOALKEEPER,
                    1f
                )

            else ->
                ClassificationResult(
                    Team.UNKNOWN,
                    0f
                )
        }
    }


    fun segment(
        frame: FrameNormalizer.NormalizedFrame
    ): JerseySegmentationResult {

        val buffer = frame.buffer
        val width = frame.width
        val height = frame.height

        val stride = width * 4

        var userPixels = 0
        var opponentPixels = 0
        var goalkeeperPixels = 0

        var y = 0

        while (y < height) {

            var x = 0

            while (x < width) {

                val index = y * stride + x * 4

                if (index + 2 < buffer.limit()) {

                    val r = buffer.get(index).toInt() and 0xFF
                    val g = buffer.get(index + 1).toInt() and 0xFF
                    val b = buffer.get(index + 2).toInt() and 0xFF

                    if (r > g + 30 && r > b + 30) {
                        userPixels++
                    } else if (b > r + 30 && b > g + 30) {
                        opponentPixels++
                    } else if (g > r + 30 && g > b + 30) {
                        goalkeeperPixels++
                    }
                }

                x += 2
            }

            y += 2
        }

        val total =
            userPixels +
            opponentPixels +
            goalkeeperPixels

        return JerseySegmentationResult(

            userPixels = userPixels,

            opponentPixels = opponentPixels,

            goalkeeperPixels = goalkeeperPixels,

            confidence =
                if (total == 0)
                    0f
                else
                    (total / 1000f).coerceIn(0f,1f)

        )
    }
}
