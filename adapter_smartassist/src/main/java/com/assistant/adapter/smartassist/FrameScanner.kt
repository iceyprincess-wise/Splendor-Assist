package com.assistant.adapter.smartassist

import java.nio.ByteBuffer
import kotlin.math.roundToInt

object FrameScanner {

    data class PixelSample(
        val x: Int,
        val y: Int,
        val red: Int,
        val green: Int,
        val blue: Int,
        val luminance: Float
    )

    fun scan(
        frame: FrameNormalizer.NormalizedFrame,
        threshold: Float = 0.60f
    ): List<PixelSample> {

        val samples = ArrayList<PixelSample>()

        val buffer: ByteBuffer =
            frame.buffer.duplicate()

        val width = frame.width
        val height = frame.height

        var y = 0

        while (y < height) {

            var x = 0

            while (x < width) {

                val index =
                    (y * width + x) * 4

                if (index + 3 < buffer.limit()) {

                    val r =
                        buffer.get(index).toInt() and 0xFF

                    val g =
                        buffer.get(index + 1).toInt() and 0xFF

                    val b =
                        buffer.get(index + 2).toInt() and 0xFF

                    val luminance =
                        (
                            0.2126f * r +
                            0.7152f * g +
                            0.0722f * b
                        ) / 255f

                    if (luminance >= threshold) {

                        samples.add(
                            PixelSample(
                                x = x,
                                y = y,
                                red = r,
                                green = g,
                                blue = b,
                                luminance = luminance
                            )
                        )
                    }
                }

                x++
            }

            y++
        }

        return samples
    }
}
