package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class DefensiveLineResult(
    val found: Boolean,
    val averageX: Float = 0f,
    val minX: Float = 0f,
    val maxX: Float = 0f,
    val confidence: Float = 0f
)

/* ========
EntityAssociationEngine
======== */
// Q68-33 (batch 5b, QD-9): the Task B repair note that lived here
// documented EntityAssociationEngine, not DefensiveLineResult — it has
// moved to the top of EntityAssociationEngine.kt (its only copy).

