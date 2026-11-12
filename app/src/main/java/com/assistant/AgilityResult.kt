package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class AgilityResult(
    val shieldActive: Boolean,
    val stabilityBoost: Float,
    val controlRetentionBoost: Float,
    val turnAssist: Float,
    val shieldAngleDegrees: Float,
    val shieldDurationMs: Long
)

/* AgilityEngine moved to NativeBridge */

/* ========
AntiCutbackSubEngine
======== */
