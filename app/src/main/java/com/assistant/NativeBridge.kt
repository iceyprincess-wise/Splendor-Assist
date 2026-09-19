package com.assistant

object NativeBridge {
    init {
        try {
            System.loadLibrary("splendor_native")
        } catch (e: Throwable) {
            android.util.Log.e("NativeBridge", "CRITICAL: Failed to load splendor_native", e)
        }
    }

    @JvmStatic
    external fun nativeKickingPosture(
        carrierX: Float, carrierY: Float,
        carrierVx: Float, carrierVy: Float,
        targetX: Float, targetY: Float,
        outBuffer: FloatArray
    )

    @JvmStatic
    external fun nativeAgilityPhysics(
        playerVelocity: Float, opponentDistance: Float,
        movementAngleDegrees: Float, possessionConfidence: Float,
        turnIntensity: Float, playerX: Float, playerY: Float,
        oppX: Float, oppY: Float, outBuffer: FloatArray
    )
}
