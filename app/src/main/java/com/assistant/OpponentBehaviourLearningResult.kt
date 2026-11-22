package com.assistant

data class OpponentBehaviourLearningResult(
    val confidence:Float=0f,
    val aggression:Float=0f,
    val pressFrequency:Float=0f,
    val transitionSpeed:Float=0f
)
