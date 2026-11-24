package com.assistant
import com.assistant.diagnostic.RuntimeLogger

object AuthorityArbitrationEngine {
    @Volatile
    private var proofLogged: Boolean = false

    fun arbitrate(
        mode: Int,
        passX: Float,
        passY: Float,
        crossX: Float,
        crossY: Float,
        predictiveX: Float,
        predictiveY: Float,
        receiver: Float,
        forward: Float,
        recovery: Float,
        shot: Float,
        stability: Float
    ): ArbitrationResult {
        // NATIVE-GUARD: the native library may fail to load; never throw from the
        // live gesture path. Fall back to the pass target (a real on-screen
        // point) instead of an arbitration blend.
        if (!com.assistant.NativeBridge.isNativeReady()) return ArbitrationResult(passX, passY)

        val packed: Long = try {
            com.assistant.NativeBridge.nativeAuthorityArbitrate(
                mode,
                passX,
                passY,
                crossX,
                crossY,
                predictiveX,
                predictiveY,
                receiver,
                forward,
                recovery,
                shot,
                stability
            )
        } catch (t: Throwable) {
            return ArbitrationResult(passX, passY)
        }

        if (!proofLogged) {
            proofLogged = true
            RuntimeLogger.log("AUTHORITY_NATIVE_ACTIVE", "NATIVE_ARBITRATION")
        }

        return ArbitrationResult(
            Float.fromBits((packed ushr 32).toInt()),
            Float.fromBits(packed.toInt())
        )
    }
}
// SPLENDOR_V31A_AUTHORITY_NO_FALLBACK_END
