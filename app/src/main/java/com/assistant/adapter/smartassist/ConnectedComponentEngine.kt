package com.assistant.adapter.smartassist

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
