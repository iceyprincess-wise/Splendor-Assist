package com.assistant

data class TelemetrySnapshot(
    // No68-E2-D11: 0 = no telemetry published yet. The old default was
    // the wall clock at construction, so the boot-time empty
    // snapshot (all zeros) read as FRESH for the whole
    // MAX_TELEMETRY_AGE_MS window instead of as no-data.
    val timestamp: Long = 0L,

    // B2 / Q68-54: SPLIT freshness timestamps. The single shared
    // `timestamp` below served both the ball and the keeper, so a keeper
    // write refreshed ball freshness (and vice versa) — the keeper scan
    // then read a stale ball as fresh. The ball writer stamps only
    // `ballTimestampMs`; consumers read it (LiveVectorResolver,
    // FrameAssembler, BallTelemetryResync). The keeper split stamp
    // (`goalkeeperTimestampMs`) is DELETED by Phase T (T-6): it had
    // zero readers tree-wide, so no consumer ever gated on keeper
    // freshness. (Legacy `timestamp` is still stamped by both writers
    // for old readers.) 0 = never written.
    val ballTimestampMs: Long = 0L,

    val playerVelocity: Float = 0f,
    // No68-E2-D12: Float.MAX_VALUE is the no-data sentinel, not a
    // measurement — a real opponent distance never reaches it. (The
    // sentinel was previously documented only in a MagneticFeetContributor
    // comment, not on the field itself.)
    val opponentDistance: Float = Float.MAX_VALUE,

    val ballX: Float = 0f,
    val ballY: Float = 0f,
    val ballVelocityX: Float = 0f,
    val ballVelocityY: Float = 0f,

    val goalkeeperX: Float = 0f,
    val goalkeeperY: Float = 0f,

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
