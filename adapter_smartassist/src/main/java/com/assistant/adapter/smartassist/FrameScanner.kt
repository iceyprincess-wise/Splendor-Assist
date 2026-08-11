package com.assistant.adapter.smartassist

import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.random.Random

object FrameScanner {

    data class PixelSample(
        val x: Int,
        val y: Int,
        val red: Int,
        val green: Int,
        val blue: Int,
        val luminance: Float
    )

    // =========================================================================
    // OMEGA UPGRADE CORE CONSTANTS
    // =========================================================================
    
    // Fixed-point multipliers for extreme coordinate translation speed.
    // Derived from (Factor * 65536) to bypass heavy floating-point operations in the hot-loop.
    private const val WEIGHT_R = 13933  // ~0.2126f
    private const val WEIGHT_G = 46871  // ~0.7152f
    private const val WEIGHT_B = 4732   // ~0.0722f
    private const val LUMINANCE_SHIFT = 16

    /**
     * Highly optimized memory-scan engine for fast pixel extraction.
     * 
     * @param frame The normalized frame input from the frame buffer.
     * @param threshold Luminance threshold for targeting (0.0f - 1.0f).
     * @param adaptiveNoiseVariance Pixel variance to maintain Humanization boundaries.
     * @param serverTickSyncScale Scales coordinates dynamically to match network packet ticks.
     */
    fun scan(
        frame: FrameNormalizer.NormalizedFrame,
        threshold: Float = 0.60f,
        adaptiveNoiseVariance: Int = 0, // 0 = no noise; ±2px was fragmenting ball blobs before BFS
        serverTickSyncScale: Float = 1.0f // Defaults to 1:1, scale for high-ping division sync
    ): List<PixelSample> {
        val width = frame.width
        val height = frame.height

        // Fallback for null or invalid frames
        if (width <= 0 || height <= 0) return emptyList()

        val buffer: ByteBuffer = frame.buffer.duplicate()
        buffer.rewind()

        val limit = buffer.remaining()
        if (limit == 0) return emptyList()

        // ---------------------------------------------------------------------
        // PHASE 1: BULK MEMORY TRANSFER
        // ---------------------------------------------------------------------
        // Extracts the full ByteBuffer payload into contiguous JVM heap memory 
        // to prevent extreme native-call latency inside the loop.
        val frameBytes = ByteArray(limit)
        buffer.get(frameBytes)

        val thresholdInt = (threshold * 255.0f).roundToInt().coerceIn(0, 255)
        
        // Pre-allocate to prevent garbage collection spikes during high-frequency looping
        val samples = ArrayList<PixelSample>(1024) 

        var index = 0
        var x = 0
        var y = 0

        // ---------------------------------------------------------------------
        // PHASE 2: HOT-LOOP PROCESSING (1D Traversal)
        // ---------------------------------------------------------------------
        while (index + 3 < limit) {
            // Bitwise extraction (avoids sign-extension bugs)
            val r = frameBytes[index].toInt() and 0xFF
            val g = frameBytes[index + 1].toInt() and 0xFF
            val b = frameBytes[index + 2].toInt() and 0xFF

            // Fast fixed-point luminance calculation (100% Integer Math)
            val lumInt = (r * WEIGHT_R + g * WEIGHT_G + b * WEIGHT_B) shr LUMINANCE_SHIFT

            if (lumInt >= thresholdInt) {
                var finalX = x
                var finalY = y

                // -------------------------------------------------------------
                // PHASE 3: KINEMATIC HUMANIZATION & SERVER TICK SYNC
                // -------------------------------------------------------------
                if (adaptiveNoiseVariance > 0 || serverTickSyncScale != 1.0f) {
                    val noiseX = if (adaptiveNoiseVariance > 0) Random.nextInt(-adaptiveNoiseVariance, adaptiveNoiseVariance + 1) else 0
                    val noiseY = if (adaptiveNoiseVariance > 0) Random.nextInt(-adaptiveNoiseVariance, adaptiveNoiseVariance + 1) else 0
                    
                    val scaledX = (x + noiseX) * serverTickSyncScale
                    val scaledY = (y + noiseY) * serverTickSyncScale

                    // Clamp to safe screen boundaries
                    finalX = scaledX.roundToInt().coerceIn(0, width - 1)
                    finalY = scaledY.roundToInt().coerceIn(0, height - 1)
                }

                samples.add(
                    PixelSample(
                        x = finalX,
                        y = finalY,
                        red = r,
                        green = g,
                        blue = b,
                        luminance = lumInt / 255f // Restore to Float format for downstream adapters
                    )
                )
            }

            // High-speed 1D-to-2D mapping
            x++
            if (x >= width) {
                x = 0
                y++
                // Failsafe boundary check to prevent out-of-bounds due to stride mismatches
                if (y >= height) break
            }

            index += 4 // Jump to next RGBA/ARGB pixel
        }

        return samples
    }
}
