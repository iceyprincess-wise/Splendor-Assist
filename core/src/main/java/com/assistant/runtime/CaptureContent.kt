package com.assistant.runtime

import java.nio.ByteBuffer

object CaptureContent {
    fun same(a: ByteBuffer, b: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        ignored: (Int, Int) -> Boolean): Boolean {
        if (width <= 0 || height <= 0 || pixelStride < 3 || rowStride.toLong() < width.toLong() * pixelStride) return false
        val required = (height - 1L) * rowStride + (width - 1L) * pixelStride + 3
        if (required > a.limit() || required > b.limit()) return false
        // CC-D1: the old `compared` flag was dead logic — the guard above
        // guarantees width >= 1 and height >= 1, so the loop always runs at
        // least once and `return compared` was always `return true`.
        // Compares the first 3 bytes (RGB) of each pixel; the alpha byte at
        // pixelStride 4 (RGBA_8888 capture) is deliberately ignored — a frame
        // differing only in alpha is visibly identical.
        // Index arithmetic cannot overflow: every index is at most
        // required - 3 (see the required check above), so y * rowStride,
        // x * pixelStride and index + 2 all stay inside Int range and
        // inside both buffers' limits.
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * rowStride + x * pixelStride
            if (a[index] != b[index] || a[index+1] != b[index+1] || a[index+2] != b[index+2]) {
                if (!ignored(x, y)) return false
            }
        }
        return true
    }
}
