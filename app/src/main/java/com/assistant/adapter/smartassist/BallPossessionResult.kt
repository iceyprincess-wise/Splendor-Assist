package com.assistant.adapter.smartassist

data class BallPossessionResult(
    val hasPossession: Boolean,
    val ownerIndex: Int = -1,
    val possessionFrames: Long = 0L,
    val possessionChanged: Boolean = false,
    val confidence: Float = 0f
)
