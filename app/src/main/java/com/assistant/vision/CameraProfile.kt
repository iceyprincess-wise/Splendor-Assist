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
 *
 * No62-D13: the profile is FIXED at DYNAMIC_WIDE. setProfile() and
 * isDynamicWide() had zero callers repo-wide, DYNAMIC_WIDE appears
 * nowhere else in the repo, and no settings surface, config or intent
 * ever selects a camera profile — so the switching API was dead and
 * not wireable without inventing a settings UI. It was removed
 * (queue-clearing decision: PROVEN totally dead, confirmed not
 * wireable). activeProfileId() stays: TrainedDetectionEngine passes
 * it to native code, and it is the seam if a second profile ever
 * exists. Do not re-add a setter without a real caller.
 * Q68-5 (batch 5b): PIXEL/TIME CONSTANTS ASSUME 288x640 CAPTURE.
 * Several engines hard-code pixel and frame constants that are only
 * correct at the 288x640 capture the pipeline forces (OverlayService's
 * ZERO_CAPTURE_GUARD — a zero capture dimension falls back to 288x640,
 * so the constants always see this geometry in production). If the
 * capture size ever changes, every one of these must be re-scaled:
 *   - PassingLaneGraphEngine.kt:163 — 500f graph distance scale
 *   - TrueTargetPassingEngine.kt:167 — 750f px/s ball speed
 *   - TruePassContributor.kt:142 — 120 px advanced-branch radius
 * The general rule: any constant in px, px/s or px/frame in a gameplay
 * engine is a capture-geometry constant, not a device-independent one.
 * OPEN (Q62-1): the 1650x720 fallback is unverified — whether the
 * constants above would still hold at that geometry is unknown, and no
 * evidence exists that the fallback ever engages in production.
 */
object CameraProfile {
    const val PROFILE_DYNAMIC_WIDE: String = "DYNAMIC_WIDE"

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
        // No62-D14: a non-positive dimension used to be stored silently,
        // and every consumer then fell back silently (captureWidth > 0
        // checks) — the stored state lied about the geometry. The only
        // caller (OverlayService:912) already guards, so this is defence
        // in depth: ignore the bad call and keep the previous scale.
        if (captureWidth > 0 && captureHeight > 0 && sourceWidth > 0 && sourceHeight > 0) {
            this.captureWidth = captureWidth
            this.captureHeight = captureHeight
            this.sourceWidth = sourceWidth
            this.sourceHeight = sourceHeight
        }
    }

    // SPLENDOR_V23A_CAMERA_HELPERS_BEGIN
    // No62-D17: the 1650f/720f fallbacks are LANDSCAPE pitch geometry
    // (aspect ~2.29, close to the 640x288 landscape fixtures the repo
    // tests use). That contradicts the live capture geometry — the
    // capture surface is portrait 288x640 on a 720x1600 device (see
    // the captureToScreen comment below and OverlayService's
    // ZERO_CAPTURE_GUARD, which forces 288x640). The fallbacks only
    // matter while capture is unset (OverlayService sets the real scale
    // at service start), but nothing in the repo proves which geometry
    // the ~40 fallback consumers actually need in that window. Queued
    // as Q62-1: verify the fallbacks against a live unset-capture
    // session instead of guessing here.
    @JvmStatic
    fun captureWidthOrFallback(): Float =
        if (captureWidth > 0) captureWidth.toFloat() else (if (sourceWidth > 0) sourceWidth.toFloat() else 1650f)

    @JvmStatic
    fun captureHeightOrFallback(): Float =
        if (captureHeight > 0) captureHeight.toFloat() else (if (sourceHeight > 0) sourceHeight.toFloat() else 720f)

    // SPLENDOR_CAPTURE_TO_SCREEN_V1 - the vision pipeline runs on a downscaled
    // capture surface (288x640 on a 720x1600 device, scale = 0.4) but
    // AccessibilityService.dispatchGesture consumes REAL screen pixels. These
    // helpers are the single declared conversion between the two spaces.
    @JvmStatic
    fun captureToScreenX(x: Float): Float =
        if (captureWidth > 0 && sourceWidth > 0) x * sourceWidth.toFloat() / captureWidth.toFloat() else x

    @JvmStatic
    fun captureToScreenY(y: Float): Float =
        if (captureHeight > 0 && sourceHeight > 0) y * sourceHeight.toFloat() / captureHeight.toFloat() else y
    // SPLENDOR_V23A_CAMERA_HELPERS_END

    // SPLENDOR_V25_CAMERA_PROOF_BEGIN
    // No62-D15: proofLogged used to be a @Volatile Boolean checked and
    // set in two steps, so two threads could both pass the check and log
    // the diagnostic twice. AtomicBoolean.compareAndSet claims the gate
    // in one atomic step.
    private val proofLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    @JvmStatic
    fun logDiagnosticsOnce() {
        if (!proofLogged.compareAndSet(false, true)) return
        try {
            com.assistant.diagnostic.RuntimeLogger.log("CAMERA_PROFILE " + diagnostics(), "CAMERA")
        } catch (_: Throwable) {
            // No62-D16: the flag used to be set BEFORE the log call, so
            // a transient RuntimeLogger failure lost the diagnostic
            // forever — the once-gate had already fired. Release the
            // claim on failure so the next call retries instead.
            proofLogged.set(false)
        }
    }
    // SPLENDOR_V25_CAMERA_PROOF_END

    @JvmStatic
    fun diagnostics(): String =
        "profile=" + PROFILE_DYNAMIC_WIDE +
            " capture=" + captureWidth + "x" + captureHeight +
            " source=" + sourceWidth + "x" + sourceHeight

    // SPLENDOR_V14A_CAMERA_PROFILE_ID_BEGIN
    const val PROFILE_ID_DYNAMIC_WIDE: Int = 1

    @JvmStatic
    fun activeProfileId(): Int = PROFILE_ID_DYNAMIC_WIDE
    // SPLENDOR_V14A_CAMERA_PROFILE_ID_END
}
