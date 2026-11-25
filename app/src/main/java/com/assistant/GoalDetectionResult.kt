package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























/** Which path produced a goal detection. */
enum class GoalDetectionSource {
    /** Direct pixel reader (PostPairReader) measured the posts. */
    READER,
    /** Blob-box fallback (GoalDetector). */
    BLOB
}

data class GoalDetectionResult(

    val detected: Boolean,

    val leftX: Float,

    val rightX: Float,

    val topY: Float,

    val bottomY: Float,

    val confidence: Float,

    /**
     * Which path produced this detection; BLOB is the default so old
     * call sites keep their meaning. The calibrator raises its width
     * bound to 85% for READER (a near goal spans ~80% of the frame)
     * and keeps 60% for BLOB.
     */
    val source: GoalDetectionSource = GoalDetectionSource.BLOB,

    /** Median per-row thickness (px) of the post bars; 0 when BLOB-sourced. */
    val barWidthLeftPx: Float = 0f,

    val barWidthRightPx: Float = 0f

)

/* ========
GoalDetector
======== */
