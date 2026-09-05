import sys
import os

base_dir = "/tmp/repo"
if not os.path.exists(base_dir):
    base_dir = os.path.expanduser("~/projects/Splendor-Assist")

fs_path = os.path.join(base_dir, "app/src/main/java/com/assistant/adapter/smartassist/FrameScanner.kt")
cce_path = os.path.join(base_dir, "app/src/main/java/com/assistant/adapter/smartassist/ConnectedComponentEngine.kt")

if not os.path.exists(base_dir):
    print(f"ERROR: Repository not found at {base_dir}. Ensure it is cloned.")
    sys.exit(1)

# 1. Rewrite FrameScanner.kt (Phase 2 + Phase 4)
fs_content = """package com.assistant.adapter.smartassist

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
"""

# 2. Rewrite ConnectedComponentEngine.kt (Phase 3 + Phase 4)
cce_content = """package com.assistant.adapter.smartassist

object ConnectedComponentEngine {

    data class Blob(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val pixelCount: Int,
        val averageRed: Float,
        val averageGreen: Float,
        val averageBlue: Float
    )

    private val OFFSETS = arrayOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to  0,          1 to  0,
        -1 to  1, 0 to  1, 1 to  1
    )

    // Reusable queue to prevent per-component ArrayDeque allocation (Phase 3)
    private val reusableQueue = java.util.ArrayDeque<Int>(1024)

    fun extract(
        buffer: FrameScanner.PixelSampleBuffer
    ): List<Blob> {
        val count = buffer.count
        if (count == 0) return emptyList()
        
        val data = buffer.data

        // Phase 3 optimization: packed Int keys (x shl 16 or y)
        // Map stores the INDEX in the PixelSampleBuffer to retrieve RGB values
        val lookup = HashMap<Int, Int>(count)
        for (i in 0 until count) {
            val packed = data[i]
            val x = (packed shr 40 and 0xFFFF).toInt()
            val y = (packed shr 24 and 0xFFFF).toInt()
            val key = (x shl 16) or y
            lookup[key] = i
        }

        val visited = HashSet<Int>(count)
        val blobs = ArrayList<Blob>()

        for (i in 0 until count) {
            val packed = data[i]
            val startX = (packed shr 40 and 0xFFFF).toInt()
            val startY = (packed shr 24 and 0xFFFF).toInt()
            val startKey = (startX shl 16) or startY

            if (!visited.add(startKey))
                continue

            reusableQueue.clear()
            reusableQueue.add(startKey)

            var minX = startX
            var minY = startY
            var maxX = startX
            var maxY = startY

            var pixelCount = 0
            var r = 0f
            var g = 0f
            var b = 0f

            while (reusableQueue.isNotEmpty()) {
                val current = reusableQueue.removeFirst()
                
                val idx = lookup[current] ?: continue
                val currentPacked = data[idx]
                
                val cx = (currentPacked shr 40 and 0xFFFF).toInt()
                val cy = (currentPacked shr 24 and 0xFFFF).toInt()
                val cr = (currentPacked shr 16 and 0xFF).toInt()
                val cg = (currentPacked shr 8 and 0xFF).toInt()
                val cb = (currentPacked and 0xFF).toInt()

                pixelCount++

                if (cx < minX) minX = cx
                if (cy < minY) minY = cy
                if (cx > maxX) maxX = cx
                if (cy > maxY) maxY = cy

                r += cr
                g += cg
                b += cb

                for ((dx, dy) in OFFSETS) {
                    val nx = cx + dx
                    val ny = cy + dy
                    val nextKey = (nx shl 16) or ny

                    if (visited.add(nextKey) && lookup.containsKey(nextKey)) {
                        reusableQueue.add(nextKey)
                    }
                }
            }

            blobs.add(
                Blob(
                    minX = minX,
                    minY = minY,
                    maxX = maxX,
                    maxY = maxY,
                    pixelCount = pixelCount,
                    averageRed = r / pixelCount,
                    averageGreen = g / pixelCount,
                    averageBlue = b / pixelCount
                )
            )
        }

        return blobs
    }
}
"""

try:
    with open(fs_path, "w", encoding="utf-8") as f:
        f.write(fs_content)
    print(f"SUCCESS: {fs_path} rewritten with Phase 2 + Phase 4 optimizations.")
    
    with open(cce_path, "w", encoding="utf-8") as f:
        f.write(cce_content)
    print(f"SUCCESS: {cce_path} rewritten with Phase 3 + Phase 4 optimizations.")
    
    print("\nNext steps: Run './gradlew build' to verify compilation, then push to main.")
except Exception as e:
    print(f"ERROR writing files: {e}")
    sys.exit(1)
