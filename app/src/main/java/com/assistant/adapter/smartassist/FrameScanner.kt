package com.assistant.adapter.smartassist

import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.random.Random

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
        threshold: Float = 0.50f,
        adaptiveNoiseVariance: Int = 0,
        serverTickSyncScale: Float = 1.0f
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

        var index = 0
        var x = 0
        var y = 0

        // PHASE 2: HOT-LOOP PROCESSING (1D Traversal)
        while (index + 3 < limit) {
            val r = frameBytes[index].toInt() and 0xFF
            val g = frameBytes[index + 1].toInt() and 0xFF
            val b = frameBytes[index + 2].toInt() and 0xFF

            val lumInt = (r * WEIGHT_R + g * WEIGHT_G + b * WEIGHT_B) shr LUMINANCE_SHIFT

            if (lumInt >= thresholdInt) {
                var finalX = x
                var finalY = y

                if (adaptiveNoiseVariance > 0 || serverTickSyncScale != 1.0f) {
                    val noiseX = if (adaptiveNoiseVariance > 0) Random.nextInt(-adaptiveNoiseVariance, adaptiveNoiseVariance + 1) else 0
                    val noiseY = if (adaptiveNoiseVariance > 0) Random.nextInt(-adaptiveNoiseVariance, adaptiveNoiseVariance + 1) else 0
                    
                    val scaledX = (x + noiseX) * serverTickSyncScale
                    val scaledY = (y + noiseY) * serverTickSyncScale

                    finalX = scaledX.roundToInt().coerceIn(0, width - 1)
                    finalY = scaledY.roundToInt().coerceIn(0, height - 1)
                }

                // Pack into Long: x(16) | y(16) | r(8) | g(8) | b(8)
                val packed = (finalX.toLong() shl 40) or 
                             (finalY.toLong() shl 24) or 
                             (r.toLong() shl 16) or 
                             (g.toLong() shl 8) or 
                             b.toLong()
                             
                samples[sampleCount++] = packed
            }

            x++
            if (x >= width) {
                x = 0
                y++
                if (y >= height) break
            }

            index += 4
        }

        return PixelSampleBuffer(samples, sampleCount)
    }
}
