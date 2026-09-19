package com.assistant

import java.util.concurrent.ThreadLocalRandom

object NativeBridge {
    init {
        try {
            System.loadLibrary("splendor_native")
        } catch (e: Throwable) {
            android.util.Log.e("NativeBridge", "CRITICAL: Failed to load splendor_native", e)
        }
    }

    /**
     * Executes bare-metal orientation vector calculation and joint torque adjustments.
     * Includes dynamic screen/pitch scaling targets to bypass hardcoded resolution boundaries.
     */
    @JvmStatic
    external fun nativeKickingPosture(
        carrierX: Float, carrierY: Float,
        carrierVx: Float, carrierVy: Float,
        targetX: Float, targetY: Float,
        pitchWidth: Float, pitchHeight: Float,
        outBuffer: FloatArray
    )

    /**
     * Executes lock-free center of mass and turn radius calculation.
     * Uses a dynamic thread seed parameter to completely isolate low-level memory states.
     */
    @JvmStatic
    external fun nativeAgilityPhysics(
        playerVelocity: Float, opponentDistance: Float,
        movementAngleDegrees: Float, possessionConfidence: Float,
        turnIntensity: Float, playerX: Float, playerY: Float,
        oppX: Float, oppY: Float, threadSeed: Int, outBuffer: FloatArray
    )

    /**
     * High-speed safe wrapper utility to pull an isolated thread seed for the native engine
     */
    @JvmStatic
    fun nextThreadSeed(): Int {
        return ThreadLocalRandom.current().nextInt()
    }
}
