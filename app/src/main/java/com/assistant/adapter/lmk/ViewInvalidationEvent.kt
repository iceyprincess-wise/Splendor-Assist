package com.assistant.adapter.lmk

data class ViewInvalidationEvent(
    val source: String,
    val critical: Boolean,
    val timestamp: Long
)
