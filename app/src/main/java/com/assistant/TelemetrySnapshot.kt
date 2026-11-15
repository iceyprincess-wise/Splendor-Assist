package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class TelemetrySnapshot(
    val timestamp: Long = System.currentTimeMillis(),

    val playerVelocity: Float = 0f,
    val opponentDistance: Float = Float.MAX_VALUE,

    val ballX: Float = 0f,
    val ballY: Float = 0f,
    val ballVelocityX: Float = 0f,
    val ballVelocityY: Float = 0f,

    val goalkeeperX: Float = 0f,
    val goalkeeperY: Float = 0f,
    val goalkeeperConfidence: Float = 0f,

    val confidence: Float = 0f,

    // SPLENDOR_P12_T2_SIZE: capture geometry the snapshot was published
    // against (real CameraProfile values; 0 = unset). Lets consumers detect
    // a mid-session rotation/restart instead of deciding on coordinates
    // from a geometry that no longer exists. The deleted fields above
    // (GK velocity/speed/heading, currentPingMs) had no writer anywhere in
    // the repo — the struct advertised measurements it never made.
    val captureWidth: Int = 0,
    val captureHeight: Int = 0
)

/* ========
TouchRecoveryEngine
======== */
