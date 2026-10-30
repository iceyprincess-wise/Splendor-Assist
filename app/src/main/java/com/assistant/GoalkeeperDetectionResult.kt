data class GoalkeeperDetectionResult(

    val detected: Boolean,

    val x: Float,

    val y: Float,

    val confidence: Float
)
/* ======
GoalkeeperDetectionResult Anchor
====== */

/* ========
GoalkeeperDetector
======== */
/**
 * PHASE4 UPGRADE: GoalkeeperDetector
 *
 * Previous state: single-frame detection, no smoothing, no coast.
 * At 15fps: one missed detection = 66ms gap. Without coast, keeper position
 * returns (0,0) on missed frames → KeeperFeedbackContributor fires to (0,0)
 * → every beast-save gesture targets the wrong position.
 *
 * Fixes:
 *  - 3-frame coast (200ms): last known keeper position held across 3 missed frames
 *    with confidence decay. Beast saves continue firing at correct position.
 *  - EWA smoothing: new detection weighted 0.65 over old 0.35 (responsive at 15fps)
 *  - MIN_CONFIDENCE = 0.18: reject weak jersey matches, reduce false positives
 */
