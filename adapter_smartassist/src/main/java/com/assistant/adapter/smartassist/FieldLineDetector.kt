package com.assistant.adapter.smartassist

object FieldLineDetector {

    fun detect(
        blobs: List<ConnectedComponentEngine.Blob>
    ): FieldLineDetectionResult 
{
        if (blobs.isEmpty()) {
            return FieldLineDetectionResult(
                touchLinesDetected = false,
                penaltyAreaDetected = false,
                centerCircleDetected = false,
                goalAreaDetected = false,
                confidence = 0f
            )
        }

        val whiteBlobs = blobs.filter {
            it.averageRed > 220f &&
            it.averageGreen > 220f &&
            it.averageBlue > 220f
        }

        val pixels =
            whiteBlobs.sumOf { it.pixelCount }

        return FieldLineDetectionResult(
            touchLinesDetected = pixels > 150,
            penaltyAreaDetected = pixels > 250,
            centerCircleDetected = pixels > 350,
            goalAreaDetected = pixels > 200,
            confidence = (pixels / 1000f).coerceIn(0f,1f)
        )
    }

}