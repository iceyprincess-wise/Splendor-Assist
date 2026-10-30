data class RuntimeConfidenceCalibrationResult(
    val confidence:Float=0f,
    val calibratedConfidence:Float=0f
)
/* ======
RuntimeConfidenceCalibrationResult Anchor
====== */

/* ========
RuntimeCoordinator
======== */
/*
 * Owns ignition order, gate state, and shutdown for the gameplay runtime.
 * Contains no gameplay logic. Gates:
 *   G0 permissions  G1 accessibility  G2 capture  G3 booster
 *   G4 engines warm G5 bus enabled    G6 runtime ready
 * For this step G0/G3 are recorded but non-blocking: a bound accessibility
 * service plus flowing projection frames already prove the permission set.
 */
