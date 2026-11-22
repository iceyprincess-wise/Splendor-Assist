package com.assistant

object VisionPreprocessor {
    // Pre-allocate output buffer for zero-alloc JNI crossing: 8 ints per blob, max 10000 blobs
    private val nativeBlobBuffer = IntArray(80000)
    private val blobPool = Array(10000) { ConnectedComponentEngine.Blob(0, 0, 0, 0, 0, 0f, 0f, 0f) }

    // No68-H-D43: native-failure transition flag — the fallback used to be
    // silent, so a dead native path was invisible. Log once on failure
    // onset and once on recovery, never per frame.
    @Volatile private var nativeFailed = false

    // The returned list is the BlobListResult singleton bound to blobPool:
    // single-frame lifetime only. NoiseFilter compacts it in place and every
    // consumer reads it inside VisionCore.process(); nobody may retain it
    // across frames (the next frame rebinds the same pool).
    fun process(frame: FrameNormalizer.NormalizedFrame): List<ConnectedComponentEngine.Blob> {
        // No68-H-D44: the `enabled` flag had zero readers — the tuning
        // panel's vision kill-switch did nothing. Gate the pipeline here
        // (default true, so default behaviour is unchanged).
        val config = VisionConfigurationEngine.current()
        if (!config.enabled) return emptyList()
        val blobCount = try {
            com.assistant.NativeBridge.nativeExtractBlobs(
                frame.buffer, frame.width, frame.height, frame.rowStride, frame.pixelStride, config.blobThreshold, nativeBlobBuffer
            )
        } catch (_: Throwable) {
            -1
        }

        if (blobCount >= 0) {
            if (nativeFailed) {
                nativeFailed = false
                try { com.assistant.diagnostic.RuntimeLogger.log("VisionPreprocessor native path recovered", "VISION") } catch (_: Throwable) {}
            }
            var actualCount = 0
            val limit = minOf(blobCount, blobPool.size)
            for (i in 0 until limit) {
                val offset = i * 8
                val count = nativeBlobBuffer[offset + 4]
                if (count > 0) {
                    val b = blobPool[actualCount]
                    b.minX = nativeBlobBuffer[offset]
                    b.minY = nativeBlobBuffer[offset + 1]
                    b.maxX = nativeBlobBuffer[offset + 2]
                    b.maxY = nativeBlobBuffer[offset + 3]
                    b.pixelCount = count
                    b.averageRed = nativeBlobBuffer[offset + 5].toFloat() / count
                    b.averageGreen = nativeBlobBuffer[offset + 6].toFloat() / count
                    b.averageBlue = nativeBlobBuffer[offset + 7].toFloat() / count
                    actualCount++
                }
            }
            BlobListResult.bind(blobPool, actualCount)
            return BlobListResult
        }

        if (!nativeFailed) {
            nativeFailed = true
            try { com.assistant.diagnostic.RuntimeLogger.log("VisionPreprocessor native path failed (blobCount=$blobCount) — Kotlin fallback active", "VISION") } catch (_: Throwable) {}
        }

        // Fallback retained until native path is 100% live-proven across all device states
        // No68-H-D42: the fallback used to ignore the configured threshold
        // (FrameScanner.scan's 0.50f default) while the native path used
        // config.blobThreshold — both paths take the same value now.
        return fallback(frame, config.blobThreshold)
    }

    private fun fallback(frame: FrameNormalizer.NormalizedFrame, threshold: Float): List<ConnectedComponentEngine.Blob> {
        val samples = FrameScanner.scan(frame, threshold)
        return ConnectedComponentEngine.extract(samples)
    }
}
