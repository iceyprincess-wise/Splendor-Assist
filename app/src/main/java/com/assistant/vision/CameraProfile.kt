package com.assistant.vision

/**
 * Central eFootball camera profile contract.
 *
 * Current target device/game setting:
 *   EFOOTBALL_CAMERA=DYNAMIC_WIDE
 *
 * This object intentionally stores only proven runtime geometry.
 * Detector-specific scaling constants must be added only after
 * repository execution-path evidence proves the required formula.
 */
object CameraProfile {
    const val PROFILE_DYNAMIC_WIDE: String = "DYNAMIC_WIDE"

    @Volatile
    var activeProfile: String = PROFILE_DYNAMIC_WIDE
        private set

    @Volatile
    var captureWidth: Int = 0
        private set

    @Volatile
    var captureHeight: Int = 0
        private set

    @Volatile
    var sourceWidth: Int = 0
        private set

    @Volatile
    var sourceHeight: Int = 0
        private set

    @JvmStatic
    fun setCaptureScale(
        captureWidth: Int,
        captureHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        this.captureWidth = captureWidth
        this.captureHeight = captureHeight
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
    }

    @JvmStatic
    fun setProfile(profile: String) {
        activeProfile = profile
    }

    @JvmStatic
    fun isDynamicWide(): Boolean = activeProfile == PROFILE_DYNAMIC_WIDE

    // SPLENDOR_V23A_CAMERA_HELPERS_BEGIN
    @JvmStatic
    fun captureWidthOrFallback(fallback: Float = 1650f): Float =
        if (captureWidth > 0) captureWidth.toFloat() else fallback

    @JvmStatic
    fun captureHeightOrFallback(fallback: Float = 720f): Float =
        if (captureHeight > 0) captureHeight.toFloat() else fallback
    // SPLENDOR_V23A_CAMERA_HELPERS_END

    // SPLENDOR_V25_CAMERA_PROOF_BEGIN
    @Volatile
    private var proofLogged: Boolean = false

    @JvmStatic
    fun logDiagnosticsOnce() {
        if (!proofLogged) {
            proofLogged = true
            try {
                com.assistant.diagnostic.RuntimeLogger.log("CAMERA_PROFILE " + diagnostics(), "CAMERA")
            } catch (_: Throwable) {
            }
        }
    }
    // SPLENDOR_V25_CAMERA_PROOF_END

    @JvmStatic
    fun diagnostics(): String =
        "profile=" + activeProfile +
            " capture=" + captureWidth + "x" + captureHeight +
            " source=" + sourceWidth + "x" + sourceHeight

    // SPLENDOR_V14A_CAMERA_PROFILE_ID_BEGIN
    const val PROFILE_ID_NORMAL: Int = 0
    const val PROFILE_ID_DYNAMIC_WIDE: Int = 1

    @JvmStatic
    fun activeProfileId(): Int =
        if (activeProfile == PROFILE_DYNAMIC_WIDE) PROFILE_ID_DYNAMIC_WIDE else PROFILE_ID_NORMAL
    // SPLENDOR_V14A_CAMERA_PROFILE_ID_END
}
