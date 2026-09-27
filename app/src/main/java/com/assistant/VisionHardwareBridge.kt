package com.assistant

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

object VisionHardwareBridge {
    private const val TAG = "VisionHardwareBridge"
    private var imageReader: ImageReader? = null
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null

    @Volatile
    var isRunning = false
        private set

    fun startCapture(width: Int, height: Int, @Suppress("UNUSED_PARAMETER") density: Int) {
        if (isRunning) return
        
        processingThread = HandlerThread("VisionHardwareThread").apply { start() }
        processingHandler = Handler(processingThread!!.looper)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imageReader = ImageReader.newInstance(
                width, height,
                PixelFormat.RGBA_8888,
                2,
                HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val planes = image.planes
                        if (planes.isNotEmpty()) {
                            val buffer = planes[0].buffer
                            NativeBridge.nativeAnalyzeFrame(
                                buffer, width, height,
                                planes[0].rowStride, planes[0].pixelStride,
                                FloatArray(67)
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Frame processing error", e)
                    } finally {
                        image.close()
                    }
                }, processingHandler)
            }
            isRunning = true
        }
    }

    fun getImageReaderSurface(): Any? = imageReader?.surface

    fun stopCapture() {
        isRunning = false
        imageReader?.close()
        imageReader = null
        processingThread?.quitSafely()
        processingThread = null
        processingHandler = null
    }
}
