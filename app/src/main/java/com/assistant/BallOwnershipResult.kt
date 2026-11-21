package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class BallOwnershipResult(
    val hasOwner: Boolean,
    val owner: TrackedPlayer? = null,
    val ownerIndex: Int = -1,
    val confidence: Float = 0f
)

/* ========
BallPossessionEngine
======== */
