package com.assistant.adapter.smartassist

object NoiseFilter {

    fun filter(
        blobs: List<ConnectedComponentEngine.Blob>,
        minimumPixels: Int = 4
    ): List<ConnectedComponentEngine.Blob> {

        if(blobs.isEmpty()){
            return emptyList()
        }

        return blobs.filter {

            it.pixelCount >= minimumPixels &&

            (it.maxX - it.minX) >= 1 &&

            (it.maxY - it.minY) >= 1

        }

    }
}
