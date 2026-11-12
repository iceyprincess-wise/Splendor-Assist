package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class PostureCorrectionResult(
    val correctedX: Float,
    val correctedY: Float,
    val balanceScore: Float,
    val requiresAdjustTouch: Boolean
)

/* KickingPostureEngine moved to NativeBridge */

/* ========
AgilityContributor
======== */
