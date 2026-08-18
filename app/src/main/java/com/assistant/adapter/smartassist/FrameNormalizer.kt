package com.assistant.adapter.smartassist

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object FrameNormalizer {

    private const val TARGET_FPS_60 = 60
    private const val TARGET_FPS_120 = 120
    private const val NANOS_PER_MS = 1_000_000L
    
    // Mathematical Precision Constants
    private const val FRAME_TIME_60HZ_NANOS = (1000L * NANOS_PER_MS) / TARGET_FPS_60
    private const val FRAME_TIME_120HZ_NANOS = (1000L * NANOS_PER_MS) / TARGET_FPS_120
    private const val SERVER_TICK_RATE_NANOS = 33L * NANOS_PER_MS // Approx 30Hz network tick interval

    private val lastProcessTimeNanos = AtomicLong(0)
    private val frameCounter = AtomicLong(0)

    data class FrameMetadata(
        val timestampNanos: Long,
        val deltaNanos: Long,
        val isTickAligned: Boolean,
        val jitterVariance: Float,
        val recommendedInputScale: Float
    )

    data class NormalizedFrame(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val metadata: FrameMetadata
    )

    /**
     * OMEGA UPGRADE: High-performance core normalization engine.
     * Incorporates frame-rate synchronization, sub-millisecond precision, and noise humanization.
     */
    fun normalize(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        targetHz: Int = TARGET_FPS_60
    ): NormalizedFrame {
        val currentTime = System.nanoTime()
        val lastTime = lastProcessTimeNanos.getAndSet(currentTime)
        val deltaNanos = if (lastTime > 0) currentTime - lastTime else 0L

        frameCounter.incrementAndGet()

        // 1. Frame-rate Synchronization & Sub-millisecond Delta Mapping
        val targetFrameTime = if (targetHz >= TARGET_FPS_120) FRAME_TIME_120HZ_NANOS else FRAME_TIME_60HZ_NANOS
        val frameSyncRatio = if (deltaNanos > 0) targetFrameTime.toFloat() / deltaNanos.toFloat() else 1.0f

        // 2. Adaptive Noise Humanization
        // Generates a dynamic micro-variance (±2%) to mimic human latency, preventing rigid heuristic flagging
        val jitterVariance = 1.0f + ((Random.nextFloat() * 0.04f) - 0.02f)

        // 3. Server-Tick Synchronization Check
        // Determines if this specific frame cycle perfectly aligns with the standard server packet boundary
        val isTickAligned = (currentTime % SERVER_TICK_RATE_NANOS) < targetFrameTime

        // 4. Amplified Input Effectiveness Calculation
        // Computes dynamic gesture scaling to force maximal packet payload effectiveness without breaking boundaries
        val baseScale = max(0.5f, min(2.0f, frameSyncRatio))
        val recommendedInputScale = if (isTickAligned) {
            baseScale * jitterVariance * 1.05f // +5% input penetration boost on perfect server tick alignment
        } else {
            baseScale * jitterVariance
        }

        val metadata = FrameMetadata(
            timestampNanos = currentTime,
            deltaNanos = deltaNanos,
            isTickAligned = isTickAligned,
            jitterVariance = jitterVariance,
            recommendedInputScale = recommendedInputScale
        )

        return NormalizedFrame(
            buffer = buffer,
            width = width,
            height = height,
            metadata = metadata
        )
    }

    /**
     * Prepares buffer for high-frequency recycling and zero-copy architectural compliance.
     */
    fun release(frame: NormalizedFrame) {
        frame.buffer.clear()
    }
}
