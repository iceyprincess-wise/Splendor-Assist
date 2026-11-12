package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class ClosestPlayerResult(
    val found: Boolean,
    val index: Int = -1,
    val distance: Float = Float.MAX_VALUE,
    val player: TrackedPlayer? = null
)

/* ========
ConfidenceHeatmap
======== */
