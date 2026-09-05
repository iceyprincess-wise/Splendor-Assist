package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import java.nio.ByteBuffer

object VisionPreprocessor {
    private const val TAG = "VisionPreprocessor"
    
    @Volatile private var nativeAvailable = false
    private var outBlobs = FloatArray(1024 * 8)
    
    init {
        try {
            System.loadLibrary("vision_preprocessor")
            nativeAvailable = true
            RuntimeLogger.log("Native VisionPreprocessor loaded", TAG)
        } catch (t: Throwable) {
            nativeAvailable = false
            RuntimeLogger.log("Native VisionPreprocessor FAILED to load: ${t.message}. Fallback active.", TAG)
        }
    }

    fun process(frame: FrameNormalizer.NormalizedFrame): List<ConnectedComponentEngine.Blob> {
        val buffer = frame.buffer
        val width = frame.width
        val height = frame.height

        if (!nativeAvailable || !buffer.isDirect) {
            return fallback(frame)
        }

        val thresholdInt = (0.50f * 255.0f).toInt().coerceIn(0, 255)
        
        while (true) {
            val capacity = outBlobs.size / 8
            val result = processFrameNative(
                buffer, width, height, width * 4, 4,
                thresholdInt, 0, 1.0f,
                outBlobs, capacity
            )
            
            if (result >= 0) {
                return decodeBlobs(result)
            } else if (result == -1) {
                return fallback(frame)
            } else {
                val required = -result
                outBlobs = FloatArray(required * 8)
            }
        }
    }

    private fun decodeBlobs(count: Int): List<ConnectedComponentEngine.Blob> {
        val list = ArrayList<ConnectedComponentEngine.Blob>(count)
        for (i in 0 until count) {
            val base = i * 8
            list.add(
                ConnectedComponentEngine.Blob(
                    minX = outBlobs[base].toInt(),
                    minY = outBlobs[base + 1].toInt(),
                    maxX = outBlobs[base + 2].toInt(),
                    maxY = outBlobs[base + 3].toInt(),
                    pixelCount = outBlobs[base + 4].toInt(),
                    averageRed = outBlobs[base + 5],
                    averageGreen = outBlobs[base + 6],
                    averageBlue = outBlobs[base + 7]
                )
            )
        }
        return list
    }

    private fun fallback(frame: FrameNormalizer.NormalizedFrame): List<ConnectedComponentEngine.Blob> {
        val samples = FrameScanner.scan(frame)
        return ConnectedComponentEngine.extract(samples)
    }

    private external fun processFrameNative(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        thresholdInt: Int, adaptiveNoiseVariance: Int, serverTickSyncScale: Float,
        outBlobs: FloatArray, outCapacity: Int
    ): Int
}
