package com.assistant

data class OffsideRiskEstimationResult(
    val lanes: List<OffsideRisk> = emptyList()
)
