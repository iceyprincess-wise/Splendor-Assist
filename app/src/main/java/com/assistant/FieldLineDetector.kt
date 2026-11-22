package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























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

        // White area alone cannot identify a specific pitch marking.
        // Preserve the appearance score, but publish semantic geometry as unknown.
        return FieldLineDetectionResult(
            touchLinesDetected = false,
            penaltyAreaDetected = false,
            centerCircleDetected = false,
            goalAreaDetected = false,
            confidence = (pixels / 400f).coerceIn(0f,1f)
        )
    }

}
