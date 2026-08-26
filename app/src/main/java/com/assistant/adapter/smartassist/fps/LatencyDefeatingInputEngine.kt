package com.assistant.adapter.smartassist.fps

import android.accessibilityservice.AccessibilityService
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import java.util.concurrent.ThreadLocalRandom
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource

/**
 * High-performance, low-overhead input injector optimized for Dynamic 15fps/20fps/30fps display 
 * sync, micro-gesture humanization, and server-tick alignment.
 * FIX: Routes strictly through CentralExecutionBus to eliminate duplicate dispatchGesture callers,
 * bypass IPC Serialization payload bloat from intermediate Path points, and maintain centralized
 * preemption and optimized 2-point path generation.
 */
class LatencyDefeatingInputEngine(
    private val service: AccessibilityService
) {
    private companion object {
        const val SERVER_TICK_WINDOW_MS = 33L   // Standard 30Hz server tick boundary
        const val MIN_STROKE_DURATION_MS = 1L
    }

    fun injectZeroLatencySwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        restrictedDuration: Long,
        currentPingMs: Int = 40
    ) {


        val random = ThreadLocalRandom.current()
        val displacementAngle = random.nextDouble(0.0, 2.0 * Math.PI)
        val jitterMagnitude = random.nextDouble(0.2, 1.4)
        val humanizedStartX = (startX + (cos(displacementAngle) * jitterMagnitude)).toFloat()
        val humanizedStartY = (startY + (sin(displacementAngle) * jitterMagnitude)).toFloat()
        val humanizedEndX = (endX + (cos(displacementAngle + Math.PI) * jitterMagnitude)).toFloat()
        val humanizedEndY = (endY + (sin(displacementAngle + Math.PI) * jitterMagnitude)).toFloat()

        val baseDistance = hypot((humanizedEndX - humanizedStartX).toDouble(), (humanizedEndY - humanizedStartY).toDouble())
        val dynamicScaleFactor = if (currentPingMs > 100) 1.12f else 1.0f
        val calibratedEndX = if (baseDistance > 0) {
            (humanizedStartX + (humanizedEndX - humanizedStartX) * dynamicScaleFactor).coerceIn(0f, 1650f)
        } else {
            humanizedEndX.coerceIn(0f, 1650f)
        }
        val calibratedEndY = if (baseDistance > 0) {
            (humanizedStartY + (humanizedEndY - humanizedStartY) * dynamicScaleFactor).coerceIn(0f, 720f)
        } else {
            humanizedEndY.coerceIn(0f, 720f)
        }

        val tickRemainder = restrictedDuration % SERVER_TICK_WINDOW_MS
        val tickCorrectedDuration = if (currentPingMs > 80 && tickRemainder != 0L) {
            (restrictedDuration + (SERVER_TICK_WINDOW_MS - tickRemainder)).coerceAtLeast(MIN_STROKE_DURATION_MS)
        } else {
            restrictedDuration.coerceAtLeast(MIN_STROKE_DURATION_MS)
        }

        val request = ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = 100, // Distinct phase for latency engine routing
            startX = humanizedStartX,
            startY = humanizedStartY,
            endX = calibratedEndX,
            endY = calibratedEndY,
            duration = tickCorrectedDuration
        )
        
        CentralExecutionBus.submit(request)
    }
}
