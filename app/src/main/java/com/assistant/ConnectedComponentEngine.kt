package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object ConnectedComponentEngine {

    class Blob(
        var minX: Int,
        var minY: Int,
        var maxX: Int,
        var maxY: Int,
        var pixelCount: Int,
        var averageRed: Float,
        var averageGreen: Float,
        var averageBlue: Float
    )

    private val OFFSETS = arrayOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to  0,          1 to  0,
        -1 to  1, 0 to  1, 1 to  1
    )

    // Reusable queue to prevent per-component ArrayDeque allocation (Phase 3)
    private val reusableQueue = ThreadLocal.withInitial { java.util.ArrayDeque<Int>(1024) }
    private val threadLocalLookup = ThreadLocal.withInitial { HashMap<Int, Int>(2048) }
    private val threadLocalVisited = ThreadLocal.withInitial { HashSet<Int>(2048) }

    fun extract(
        buffer: FrameScanner.PixelSampleBuffer
    ): List<Blob> {
        val count = buffer.count
        if (count == 0) return emptyList()
        
        val data = buffer.data

        // Phase 3 optimization: packed Int keys (x shl 16 or y)
        // Map stores the INDEX in the PixelSampleBuffer to retrieve RGB values
        val lookup = threadLocalLookup.get()!!
        lookup.clear()
        for (i in 0 until count) {
            val packed = data[i]
            val x = (packed shr 40 and 0xFFFF).toInt()
            val y = (packed shr 24 and 0xFFFF).toInt()
            val key = (x shl 16) or y
            lookup[key] = i
        }

        val visited = threadLocalVisited.get()!!
        visited.clear()
        val blobs = ArrayList<Blob>()
        val queue = reusableQueue.get()!!

        for (i in 0 until count) {
            val packed = data[i]
            val startX = (packed shr 40 and 0xFFFF).toInt()
            val startY = (packed shr 24 and 0xFFFF).toInt()
            val startKey = (startX shl 16) or startY

            if (!visited.add(startKey))
                continue

            queue.clear()
            queue.add(startKey)

            var minX = startX
            var minY = startY
            var maxX = startX
            var maxY = startY

            val startKind = ForegroundPixel.kind((packed shr 16 and 255).toInt(), (packed shr 8 and 255).toInt(), (packed and 255).toInt())
            var pixelCount = 0
            var r = 0f
            var g = 0f
            var b = 0f

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                
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

                    if (nx < 0 || ny < 0) continue
                    val neighbor = lookup[nextKey] ?: continue
                    val rgb = data[neighbor]
                    if (ForegroundPixel.kind((rgb shr 16 and 255).toInt(), (rgb shr 8 and 255).toInt(), (rgb and 255).toInt()) != startKind) continue
                    if (visited.add(nextKey)) queue.add(nextKey)
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

/* ========
CounterPressRecognitionEngine
======== */
