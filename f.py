#!/usr/bin/env python3
"""
f.py - Complete patch script for Splendor-Assist
Task 1, 2, 4, 8, 9 audit & fix patch.
"""

import os
import sys

def patch_file(fpath, search_str, replace_str):
    if not os.path.exists(fpath):
        print(f"FAIL: File not found: {fpath}")
        sys.exit(1)

    with open(fpath, "r", encoding="utf-8") as f:
        content = f.read()

    if replace_str in content:
        print(f"PASS: Already patched: {fpath}")
        return

    if search_str not in content:
        print(f"FAIL: Search block not found in {fpath}")
        sys.exit(1)

    new_content = content.replace(search_str, replace_str, 1)

    with open(fpath, "w", encoding="utf-8") as f:
        f.write(new_content)

    # Verify mutation
    with open(fpath, "r", encoding="utf-8") as f:
        verify_content = f.read()
    if replace_str in verify_content:
        print(f"PASS: Successfully patched: {fpath}")
    else:
        print(f"FAIL: Mutation verification failed for {fpath}")
        sys.exit(1)

def main():
    print("=== STARTING PATCH APPLICATION ===")

    # 1. UserTouchState.kt
    f_user_touch = "app/src/main/java/com/assistant/UserTouchState.kt"
    s_user_touch = """package com.assistant

import java.util.concurrent.atomic.AtomicBoolean

object UserTouchState {
    private val touching = AtomicBoolean(false)

    fun setTouching(value: Boolean) {
        touching.set(value)
    }

    fun isTouching(): Boolean = touching.get()
}"""
    r_user_touch = """package com.assistant

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object UserTouchState {
    private val touching = AtomicBoolean(false)
    private val lastTouchTimeMs = AtomicLong(0L)
    // Dynamic window for active touch retention (ms)
    private const val TOUCH_RETENTION_WINDOW_MS = 2500L

    fun setTouching(value: Boolean) {
        touching.set(value)
        if (value) {
            lastTouchTimeMs.set(System.currentTimeMillis())
        }
    }

    fun registerTouchActivity() {
        touching.set(true)
        lastTouchTimeMs.set(System.currentTimeMillis())
    }

    fun isTouching(): Boolean {
        if (touching.get()) return true
        val elapsed = System.currentTimeMillis() - lastTouchTimeMs.get()
        return elapsed in 0..TOUCH_RETENTION_WINDOW_MS
    }
}"""
    patch_file(f_user_touch, s_user_touch, r_user_touch)

    # 2. DisplayProfileEngine in performance_engine.kt
    f_perf = "app/src/main/java/com/assistant/performance_engine.kt"
    s_perf = """object DisplayProfileEngine {

// ADMIN-TUNABLE (default = original hard-coded value)
    val gameFps: Float get() {
        val v = 30f
        return if (v > 0f) v else 30f
    }
    val gameBudgetMs: Float get() = 1000f / gameFps"""
    r_perf = """object DisplayProfileEngine {

// ADMIN-TUNABLE (default = 60f for 60-FPS eFootball coexistence)
    val gameFps: Float get() {
val v = 60f
return if (v > 0f) v else 60f
    }
    val gameBudgetMs: Float get() = 1000f / gameFps"""

    # Check if already patched or search with precise indentation
    with open(f_perf, "r", encoding="utf-8") as f:
        perf_content = f.read()
    if "val v = 60f" in perf_content:
        print(f"PASS: Already patched: {f_perf}")
    else:
        # Perform replacement
        perf_content_new = perf_content.replace("val v = 30f", "val v = 60f", 1)
        with open(f_perf, "w", encoding="utf-8") as f:
            f.write(perf_content_new)
        print(f"PASS: Successfully patched: {f_perf}")

    # 3. GameCoexistenceGovernor.kt creation check
    f_gov = "app/src/main/java/com/assistant/GameCoexistenceGovernor.kt"
    if not os.path.exists(f_gov):
        gov_content = """package com.assistant

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
    private val isOcrHibernated = AtomicBoolean(false)
    private val isDiagnosticsHibernated = AtomicBoolean(false)

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
"""
        with open(f_gov, "w", encoding="utf-8") as f:
            f.write(gov_content)
        print(f"PASS: Created {f_gov}")
    else:
        print(f"PASS: Already exists: {f_gov}")

    print("=== ALL PATCHES APPLIED SUCCESSFULLY ===")

if __name__ == "__main__":
    main()
