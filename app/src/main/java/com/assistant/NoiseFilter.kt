package com.assistant

import com.assistant.diagnostic.RuntimeLogger

object NoiseFilter {

    fun filter(
        blobs: List<ConnectedComponentEngine.Blob>,
        minimumPixels: Int = 4
    ): List<ConnectedComponentEngine.Blob> {

        if(blobs.isEmpty()){
            return emptyList()
        }

        if (blobs is BlobListResult) {
            val pool = blobs.pool
            var writeIdx = 0
            var maxDroppedPx = 0
            for (i in 0 until blobs.size) {
                val b = blobs[i]
                if (b.pixelCount >= minimumPixels && (b.maxX - b.minX) >= 1 && (b.maxY - b.minY) >= 1) {
                    if (writeIdx != i) {
                        val target = pool[writeIdx]
                        target.minX = b.minX
                        target.minY = b.minY
                        target.maxX = b.maxX
                        target.maxY = b.maxY
                        target.pixelCount = b.pixelCount
                        target.averageRed = b.averageRed
                        target.averageGreen = b.averageGreen
                        target.averageBlue = b.averageBlue
                    }
                    writeIdx++
                } else {
                    if (b.pixelCount > maxDroppedPx) maxDroppedPx = b.pixelCount
                }
            }
            BlobListResult.bind(pool, writeIdx)
            // LOGGAPS: upstream ball-loss evidence — the frame-style
            // question needs to know whether the NoiseFilter min-pixel
            // gate drops a ball-sized blob. Log only when a dropped
            // blob reached 3 px (1-2 px drops are noise; a 3 px drop
            // is the boundary case). The plain-list fallback path
            // below has no drop log (test-only path; production
            // passes BlobListResult).
            val dropped = blobs.size - writeIdx
            if (maxDroppedPx >= 3) {
                try { RuntimeLogger.log("NOISEFILTER dropped=$dropped maxDroppedPx=$maxDroppedPx minPx=$minimumPixels", "VISION") } catch (_: Throwable) {}
            }
            return BlobListResult
        }

        return blobs.filter {

            it.pixelCount >= minimumPixels &&

            (it.maxX - it.minX) >= 1 &&

            (it.maxY - it.minY) >= 1

        }

    }
}
