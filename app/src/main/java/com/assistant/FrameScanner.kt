package com.assistant






































import com.assistant.runtime.*












import java.nio.ByteBuffer













import kotlin.math.*










import kotlin.math.roundToInt

















object FrameScanner {

    // Reusable buffer to prevent per-frame ~1.6MB ByteArray allocations (Phase 2)
    private var reusableByteArray: ByteArray? = null
    
    // Reusable buffer for packed pixel samples to prevent per-frame object allocations (Phase 4)
    private var reusableSamples: LongArray? = null

    data class PixelSampleBuffer(
        val data: LongArray,
        val count: Int
    )

    // =========================================================================
    // OMEGA UPGRADE CORE CONSTANTS
    // =========================================================================
    
    private const val WEIGHT_R = 13933  // ~0.2126f
    private const val WEIGHT_G = 46871  // ~0.7152f
    private const val WEIGHT_B = 4732   // ~0.0722f
    private const val LUMINANCE_SHIFT = 16

    fun scan(
        frame: FrameNormalizer.NormalizedFrame,
        threshold: Float = 0.50f
    ): PixelSampleBuffer {
        val width = frame.width
        val height = frame.height

        if (width <= 0 || height <= 0) return PixelSampleBuffer(LongArray(0), 0)

        val buffer: ByteBuffer = frame.buffer.duplicate()
        buffer.rewind()

        val limit = buffer.remaining()
        if (limit == 0) return PixelSampleBuffer(LongArray(0), 0)

        // PHASE 1: BULK MEMORY TRANSFER (ZERO-ALLOC REUSE)
        var frameBytes = reusableByteArray
        if (frameBytes == null || frameBytes.size < limit) {
            frameBytes = ByteArray(limit)
            reusableByteArray = frameBytes
        }
        buffer.get(frameBytes, 0, limit)

        val thresholdInt = (threshold * 255.0f).roundToInt().coerceIn(0, 255)
        
        // PHASE 4: ZERO-ALLOC PIXEL SAMPLES
        val maxPixels = width * height
        var samples = reusableSamples
        if (samples == null || samples.size < maxPixels) {
            samples = LongArray(maxPixels)
            reusableSamples = samples
        }
        
        var sampleCount = 0

        val required = (height - 1L) * frame.rowStride + (width - 1L) * frame.pixelStride + 3
        if (frame.pixelStride < 3 || frame.rowStride.toLong() < width.toLong() * frame.pixelStride || required > limit)
            return PixelSampleBuffer(samples, 0)
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * frame.rowStride + x * frame.pixelStride
            val r = frameBytes[index].toInt() and 0xFF
            val g = frameBytes[index + 1].toInt() and 0xFF
            val b = frameBytes[index + 2].toInt() and 0xFF
            if (ForegroundPixel.retained(r, g, b, thresholdInt)) {
                // The adaptive-noise / server-tick-sync parameters were
                // removed: no production or test caller ever passed them,
                // so pixel coordinates were never actually jittered or
                // rescaled despite the code implying otherwise.
                samples[sampleCount++] = (x.toLong() shl 40) or (y.toLong() shl 24) or
                    (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
            }
        }

        return PixelSampleBuffer(samples, sampleCount)
    }
}

/* ========
GameStateBuilder
======== */
