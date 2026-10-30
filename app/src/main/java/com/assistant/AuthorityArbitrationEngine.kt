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
        val packed = com.assistant.NativeBridge.nativeAuthorityArbitrate(
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
/* ======
AuthorityArbitrationEngine Anchor
====== */
