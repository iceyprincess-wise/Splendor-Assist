package com.assistant



































































data class ViewInvalidationEvent(
    val source: String,
    val critical: Boolean,
    val timestamp: Long
)

/* ========
ViewInvalidationFilter
======== */
