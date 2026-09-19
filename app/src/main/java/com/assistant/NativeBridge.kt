package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

object NativeBridge {
    private const val LIBRARY = "splendor_native"
    private val nativeReady = AtomicBoolean(false)
    
    @Volatile
    private var nativeFailure = ""
    
    @Volatile
    private var failureLogged = false

    init {
        try {
            System.loadLibrary(LIBRARY)
            nativeReady.set(true)
            RuntimeLogger.log("splendor_native loaded; explicit JNI registration available.", "NativeBridge")
        } catch (e: Throwable) {
            nativeFailure = e.message ?: "Unknown load error"
            nativeReady.set(false)
            android.util.Log.e("NativeBridge", "CRITICAL: Failed to load $LIBRARY", e)
        }
    }

    @JvmStatic
    fun isNativeReady(): Boolean = nativeReady.get()

    private fun logFailureOnce() {
        if (!failureLogged) {
            failureLogged = true
            RuntimeLogger.log("Native library unavailable - $nativeFailure", "NativeBridge")
        }
    }

    @JvmStatic
    fun nativeKickingPosture(
        carrierX: Float, carrierY: Float,
        carrierVx: Float, carrierVy: Float,
        targetX: Float, targetY: Float,
        pitchWidth: Float, pitchHeight: Float,
        outBuffer: FloatArray
    ) {
        if (!nativeReady.get()) {
            logFailureOnce()
            return
        }
        nativeKickingPostureImpl(
            carrierX, carrierY, carrierVx, carrierVy,
            targetX, targetY, pitchWidth, pitchHeight, outBuffer
        )
    }

    @JvmStatic
    fun nativeAgilityPhysics(
        playerVelocity: Float, opponentDistance: Float,
        movementAngleDegrees: Float, possessionConfidence: Float,
        turnIntensity: Float, playerX: Float, playerY: Float,
        oppX: Float, oppY: Float, threadSeed: Int, outBuffer: FloatArray
    ) {
        if (!nativeReady.get()) {
            logFailureOnce()
            return
        }
        nativeAgilityPhysicsImpl(
            playerVelocity, opponentDistance, movementAngleDegrees, possessionConfidence,
            turnIntensity, playerX, playerY, oppX, oppY, threadSeed, outBuffer
        )
    }

    @JvmStatic
    private external fun nativeKickingPostureImpl(
        carrierX: Float, carrierY: Float,
        carrierVx: Float, carrierVy: Float,
        targetX: Float, targetY: Float,
        pitchWidth: Float, pitchHeight: Float,
        outBuffer: FloatArray
    )

    @JvmStatic
    private external fun nativeAgilityPhysicsImpl(
        playerVelocity: Float, opponentDistance: Float,
        movementAngleDegrees: Float, possessionConfidence: Float,
        turnIntensity: Float, playerX: Float, playerY: Float,
        oppX: Float, oppY: Float, threadSeed: Int, outBuffer: FloatArray
    )

    @JvmStatic
    fun nextThreadSeed(): Int {
        return ThreadLocalRandom.current().nextInt()
    }
}
