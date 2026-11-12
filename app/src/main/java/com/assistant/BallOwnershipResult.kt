package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class BallOwnershipResult(
    val hasOwner: Boolean,
    val owner: TrackedPlayer? = null,
    val ownerIndex: Int = -1,
    val distanceToBall: Float = Float.MAX_VALUE,
    val confidence: Float = 0f
)

/* ========
BallPossessionEngine
======== */
