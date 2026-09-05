package com.assistant.adapter.smartassist

import java.util.ArrayDeque

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

    // Reusable queue to prevent per-component ArrayDeque allocation
    private val reusableQueue = java.util.ArrayDeque<Int>(1024)

    fun extract(
        samples: List<FrameScanner.PixelSample>
    ): List<Blob> {

        if (samples.isEmpty()) return emptyList()

        // Primitive Int packing: (x shl 16) or y.
        // Safe because max capture width/height < 65536.
        val lookup = HashMap<Int, FrameScanner.PixelSample>(samples.size)

        samples.forEach {
            val packed = (it.x shl 16) or it.y
            lookup[packed] = it
        }

        val visited = HashSet<Int>(samples.size)
        val blobs = ArrayList<Blob>()

        for (sample in samples) {
            val start = (sample.x shl 16) or sample.y

            if (!visited.add(start))
                continue

            reusableQueue.clear()
            reusableQueue.add(start)

            var minX = sample.x
            var minY = sample.y
            var maxX = sample.x
            var maxY = sample.y

            var count = 0

            var r = 0f
            var g = 0f
            var b = 0f

            while (reusableQueue.isNotEmpty()) {
                val current = reusableQueue.removeFirst()
                
                val pixel = lookup[current] ?: continue

                val cx = current ushr 16
                val cy = current and 0xFFFF

                count++

                if (cx < minX) minX = cx
                if (cy < minY) minY = cy
                if (cx > maxX) maxX = cx
                if (cy > maxY) maxY = cy

                r += pixel.red
                g += pixel.green
                b += pixel.blue

                for ((dx, dy) in OFFSETS) {
                    val nx = cx + dx
                    val ny = cy + dy
                    val next = (nx shl 16) or ny

                    if (visited.add(next) && lookup.containsKey(next)) {
                        reusableQueue.add(next)
                    }
                }
            }

            blobs.add(
                Blob(
                    minX = minX,
                    minY = minY,
                    maxX = maxX,
                    maxY = maxY,
                    pixelCount = count,
                    averageRed = r / count,
                    averageGreen = g / count,
                    averageBlue = b / count
                )
            )
        }

        return blobs
    }
}
