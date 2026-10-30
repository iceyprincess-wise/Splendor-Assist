package com.assistant

import android.os.SystemClock
import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * GameCoexistenceGovernor (Task 4)
 *
 * Smooths and budgets Splendor-Assist execution alongside 60-FPS eFootball (jp.konami.pesam).
 * Ensures critical Vision -> Decision -> Gesture Dispatch path is NEVER hibernated or blocked,
 * while non-critical workloads (OCR, heavy diagnostics, learning loops, disk logging) are
 * dynamically hibernated or throttled under system load.
 */
object GameCoexistenceGovernor {

    enum class PressureLevel {
        GREEN,   // Full headroom - all subsystems active
        YELLOW,  // Moderate load - throttle non-critical analytics
        RED      // Heavy load - hibernate OCR, learning, and non-critical logging
    }

    @Volatile
    private var currentPressure = PressureLevel.GREEN

    private val frameCounter = AtomicInteger(0)

    @Volatile
    private var lastFrameTimeMs = 0L

    fun onFrameStart() {
        val now = SystemClock.elapsedRealtime()
        if (lastFrameTimeMs > 0) {
            val deltaMs = now - lastFrameTimeMs
            // If frame delta exceeds 22ms (~45 FPS dropped), step up pressure
            if (deltaMs > 30) {
                currentPressure = PressureLevel.RED
            } else if (deltaMs > 22) {
                currentPressure = PressureLevel.YELLOW
            } else {
                currentPressure = PressureLevel.GREEN
            }
        }
        lastFrameTimeMs = now
        frameCounter.incrementAndGet()
    }

    fun getPressure(): PressureLevel = currentPressure

    fun shouldRunOcr(): Boolean {
        if (currentPressure == PressureLevel.RED) return false
        if (currentPressure == PressureLevel.YELLOW) {
            return frameCounter.get() % 4 == 0
        }
        return true
    }

    fun shouldRunAnalytics(): Boolean {
        if (currentPressure == PressureLevel.RED) return false
        if (currentPressure == PressureLevel.YELLOW) {
            return frameCounter.get() % 2 == 0
        }
        return true
    }

    fun shouldRunDiagnostics(): Boolean {
        return currentPressure != PressureLevel.RED
    }

    fun logState() {
        RuntimeLogger.log(
            "CoexistenceGovernor pressure=" + currentPressure.name +
                    " frame=" + frameCounter.get(),
            "GOVERNOR"
        )
    }
}
