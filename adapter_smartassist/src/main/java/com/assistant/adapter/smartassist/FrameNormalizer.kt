package com.assistant.adapter.smartassist

import java.nio.ByteBuffer

object FrameNormalizer {

    data class NormalizedFrame(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int
    )

    fun normalize(
        buffer: ByteBuffer,
        width: Int,
        height: Int
    ): NormalizedFrame {

        return NormalizedFrame(
            buffer = buffer,
            width = width,
            height = height
        )
    }
}
