object VisionPreprocessor {
    private const val TAG = "VisionPreprocessor"
    
    // Pre-allocate output buffer for zero-alloc JNI crossing: 8 ints per blob, max 10000 blobs
    private val nativeBlobBuffer = IntArray(80000)
    private val blobPool = Array(10000) { ConnectedComponentEngine.Blob(0, 0, 0, 0, 0, 0f, 0f, 0f) }

    fun process(frame: FrameNormalizer.NormalizedFrame): List<ConnectedComponentEngine.Blob> {
        val blobCount = try {
            com.assistant.NativeBridge.nativeExtractBlobs(
                frame.buffer, frame.width, frame.height, frame.rowStride, frame.pixelStride, 0.50f, nativeBlobBuffer
            )
        } catch (_: Throwable) {
            -1
        }

        if (blobCount >= 0) {
            var actualCount = 0
            val limit = minOf(blobCount, blobPool.size)
            for (i in 0 until limit) {
                if (actualCount >= blobPool.size) break
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

        // Fallback retained until native path is 100% live-proven across all device states
        return fallback(frame)
    }

    private fun fallback(frame: FrameNormalizer.NormalizedFrame): List<ConnectedComponentEngine.Blob> {
        val samples = FrameScanner.scan(frame)
        return ConnectedComponentEngine.extract(samples)
    }
}
/* ======
VisionPreprocessor Anchor
====== */

/* ========
ControlMappingTrainer
======== */
/**
 * ControlMappingTrainer - button-mapping ground truth + personal timing trainer.
 * Trained from the user's mapping screenshots (Attacking/Defending), stored
 * normalized so any capture resolution works. Probe-only: <=43 byte reads per
 * frame, never a full-frame pass, so the 60fps hot path is untouched.
 */
