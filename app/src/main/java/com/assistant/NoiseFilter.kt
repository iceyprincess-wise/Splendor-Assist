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
                }
            }
            BlobListResult.bind(pool, writeIdx)
            return BlobListResult
        }

        return blobs.filter {

            it.pixelCount >= minimumPixels &&

            (it.maxX - it.minX) >= 1 &&

            (it.maxY - it.minY) >= 1

        }

    }
}
/* ======
NoiseFilter Anchor
====== */

/* ========
OffensiveLineEngine
======== */
