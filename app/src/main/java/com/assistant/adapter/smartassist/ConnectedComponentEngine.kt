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

    private data class Key(val x: Int, val y: Int)

    private val OFFSETS = arrayOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to  0,          1 to  0,
        -1 to  1, 0 to  1, 1 to  1
    )

    fun extract(
        samples: List<FrameScanner.PixelSample>
    ): List<Blob> {

        if (samples.isEmpty()) return emptyList()

        val lookup = HashMap<Key, FrameScanner.PixelSample>(samples.size)

        samples.forEach {
            lookup[Key(it.x, it.y)] = it
        }

        val visited = HashSet<Key>()

        val blobs = ArrayList<Blob>()

        for (sample in samples) {

            val start = Key(sample.x, sample.y)

            if (!visited.add(start))
                continue

            val queue = ArrayDeque<Key>()
            queue.add(start)

            var minX = sample.x
            var minY = sample.y
            var maxX = sample.x
            var maxY = sample.y

            var count = 0

            var r = 0f
            var g = 0f
            var b = 0f

            while (queue.isNotEmpty()) {

                val current = queue.removeFirst()

                val pixel =
                    lookup[current] ?: continue

                count++

                if (pixel.x < minX) minX = pixel.x
                if (pixel.y < minY) minY = pixel.y
                if (pixel.x > maxX) maxX = pixel.x
                if (pixel.y > maxY) maxY = pixel.y

                r += pixel.red
                g += pixel.green
                b += pixel.blue

                for ((dx, dy) in OFFSETS) {

                    val next =
                        Key(
                            current.x + dx,
                            current.y + dy
                        )

                    if (visited.add(next) &&
                        lookup.containsKey(next)
                    ) {
                        queue.add(next)
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
