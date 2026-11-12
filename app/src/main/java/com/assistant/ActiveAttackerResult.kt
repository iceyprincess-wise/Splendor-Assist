package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class ActiveAttackerResult(
    val found: Boolean,
    val attacker: TrackedPlayer? = null,
    val attackerIndex: Int = -1,
    val confidence: Float = 0f
)

/* ========
ActiveDefenderEngine
======== */
