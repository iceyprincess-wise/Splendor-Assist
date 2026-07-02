package com.assistant.adapter.smartassist

data class PlayerTendencyLearningResult(
    val confidence:Float=0f,
    val passBias:Float=0f,
    val dribbleBias:Float=0f,
    val shootBias:Float=0f
)
