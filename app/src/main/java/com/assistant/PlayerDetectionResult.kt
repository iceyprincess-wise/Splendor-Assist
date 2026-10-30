data class PlayerDetectionResult(
    val detected: Boolean,
    val playerCount: Int,
    val confidence: Float,
    val detections: List<PlayerDetection>
)
/* ======
PlayerDetectionResult Anchor
====== */

/* ========
PlayerTendencyLearningEngine
======== */
