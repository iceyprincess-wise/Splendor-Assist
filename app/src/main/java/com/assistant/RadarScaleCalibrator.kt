package com.assistant

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

object RadarScaleCalibrator {
    @Volatile
    var screenWidth: Int = 1080
        private set
    @Volatile
    var screenHeight: Int = 2340
        private set
        
    @Volatile
    var pitchScaleX: Float = 1.0f
        private set
    @Volatile
    var pitchScaleY: Float = 1.0f
        private set

    private const val LOGICAL_PITCH_WIDTH = 105.0f
    private const val LOGICAL_PITCH_HEIGHT = 68.0f

    fun calibrate(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        val targetPitchWidthPx = screenWidth * 0.70f
        val targetPitchHeightPx = screenHeight * 0.45f
        
        pitchScaleX = targetPitchWidthPx / LOGICAL_PITCH_WIDTH
        pitchScaleY = targetPitchHeightPx / LOGICAL_PITCH_HEIGHT
    }

    fun mapToScreen(logicalX: Float, logicalY: Float): FloatArray {
        val screenX = (logicalX * pitchScaleX) + (screenWidth * 0.15f)
        val screenY = (logicalY * pitchScaleY) + (screenHeight * 0.275f)
        return floatArrayOf(screenX, screenY)
    }
}
