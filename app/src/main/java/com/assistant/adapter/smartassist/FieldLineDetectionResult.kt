package com.assistant.adapter.smartassist

data class FieldLineDetectionResult(

    val touchLinesDetected: Boolean,

    val penaltyAreaDetected: Boolean,

    val centerCircleDetected: Boolean,

    val goalAreaDetected: Boolean,

    val confidence: Float

)
