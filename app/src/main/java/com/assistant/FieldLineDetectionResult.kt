package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class FieldLineDetectionResult(

    val touchLinesDetected: Boolean,

    val penaltyAreaDetected: Boolean,

    val centerCircleDetected: Boolean,

    val goalAreaDetected: Boolean,

    val confidence: Float

)

/* ========
FieldLineDetector
======== */
