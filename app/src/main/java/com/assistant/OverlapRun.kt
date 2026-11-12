package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class OverlapRun(
    val runner:TrackedPlayer,
    val supportX:Float,
    val supportY:Float,
    val confidence:Float,
    val viable:Boolean
)

