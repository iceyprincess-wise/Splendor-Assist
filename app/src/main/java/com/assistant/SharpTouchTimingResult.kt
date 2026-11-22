package com.assistant

data class SharpTouchTimingResult(
    val executeNow: Boolean,
    val optimalFrameOffset: Int,
    val timingAuthority: Float
)
// No68-I-D9: ballDistancePx deleted — written by the engine, read by no
// consumer anywhere in the repo (same dead-field class as No67-D30).
