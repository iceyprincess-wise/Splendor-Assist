package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class JerseySegmentationResult(

    val userPixels: Int,

    val opponentPixels: Int,

    val goalkeeperPixels: Int,

    val confidence: Float

)

/* ========
LiveVectorResolver
======== */
/**
 * Resolves Smart Assist action coordinates.
 * Uses live telemetry when the scan has real motion; otherwise falls back to
 * active screen-relative vectors so the engines NEVER go silent.
 */
