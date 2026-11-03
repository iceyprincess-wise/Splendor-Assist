package com.assistant.runtime

import java.nio.ByteBuffer

object CaptureContent {
    fun same(a: ByteBuffer, b: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        ignored: (Int, Int) -> Boolean): Boolean {
        if (width <= 0 || height <= 0 || pixelStride < 3 || rowStride.toLong() < width.toLong() * pixelStride) return false
        val required = (height - 1L) * rowStride + (width - 1L) * pixelStride + 3
        if (required > a.limit() || required > b.limit()) return false
        var compared = false
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * rowStride + x * pixelStride
            if (a[index] != b[index] || a[index+1] != b[index+1] || a[index+2] != b[index+2]) {
                if (!ignored(x, y)) return false
            }
            compared = true
        }
        return compared
    }
}
