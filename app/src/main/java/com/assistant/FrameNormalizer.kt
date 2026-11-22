package com.assistant

// F49_PATCH_V1

import java.nio.ByteBuffer

object FrameNormalizer {

    data class FrameMetadata(
        // Letter F (item 19): deltaNanos deleted — PROVEN zero readers
        // tree-wide; timestampNanos stays (read live by VisionCore
        // :144/:219/:236 and TrainedDetectionEngine:319).
        val timestampNanos: Long,
    )

    data class NormalizedFrame(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val pixelStride: Int,
        val metadata: FrameMetadata
    )

    /**
     * Normalization core: validates the capture buffer geometry and stamps
     * per-frame timing metadata (capture timestamp + inter-frame delta).
     * (Batch 6d: the old doc claimed this added noise — it adds none and
     * humanizes nothing; it wraps the buffer + timing.)
     */
    fun normalize(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int = width * 4,
        pixelStride: Int = 4,
        capturedAtNanos: Long = System.nanoTime()
    ): NormalizedFrame {
        require(width > 0 && height > 0 && pixelStride >= 3 && rowStride.toLong() >= width.toLong() * pixelStride)
        require((height - 1L) * rowStride + (width - 1L) * pixelStride + 3 <= buffer.limit())
        val currentTime = capturedAtNanos


        val metadata = FrameMetadata(
            timestampNanos = currentTime,
        )

        return NormalizedFrame(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            metadata = metadata
        )
    }

}

/* ========
FrameScanner
======== */
