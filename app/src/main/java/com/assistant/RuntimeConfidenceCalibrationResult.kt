package com.assistant

data class RuntimeConfidenceCalibrationResult(
    // R-5 (Phase R): the raw confidence field is deleted — zero readers;
    // the only value any caller reads is calibratedConfidence.
    val calibratedConfidence:Float=0f
)
