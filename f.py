#!/usr/bin/env python3
import os
import sys

REPO_ROOT = "/data/data/com.termux/files/home/projects/Splendor-Assist"
gameplay_path = os.path.join(REPO_ROOT, "app/src/main/java/com/assistant/gameplay_engine.kt")

if not os.path.exists(gameplay_path):
    print(f"ERROR: {gameplay_path} not found")
    sys.exit(1)

with open(gameplay_path, 'r') as f:
    content = f.read()

old_tap = """    @JvmStatic
    fun injectZeroLatencyTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        
        // Build the gesture with absolute minimal duration for instant registration
        val stroke = GestureDescription.StrokeDescription(path, 0, OVERRIDE_LATENCY_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        return GestureExecutionAuthority.execute(service, gesture, null, null)
    }"""

new_tap = """    @JvmStatic
    fun injectZeroLatencyTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        return com.assistant.input.NativeInputBridge.injectTap(service, x, y)
    }"""

old_swipe = """    @JvmStatic
    fun injectStabilizedSwipe(
        service: AccessibilityService, 
        startX: Float, 
        startY: Float, 
        endX: Float, 
        endY: Float, 
        durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        
        // Ensure duration doesn't violate engine bounds but pushes the hardware limit
        val safeDuration = max(OVERRIDE_LATENCY_MS, durationMs)
        
        val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        return GestureExecutionAuthority.execute(service, gesture, null, null)
    }"""

new_swipe = """    @JvmStatic
    fun injectStabilizedSwipe(
        service: AccessibilityService, 
        startX: Float, 
        startY: Float, 
        endX: Float, 
        endY: Float, 
        durationMs: Long
    ): Boolean {
        val safeDuration = max(OVERRIDE_LATENCY_MS, durationMs)
        return com.assistant.input.NativeInputBridge.injectSwipe(service, startX, startY, endX, endY, safeDuration)
    }"""

patched = False
if old_tap in content:
    content = content.replace(old_tap, new_tap, 1)
    print("PATCHED: TouchStabilizationEngine (Tap) - Dead code eliminated")
    patched = True
else:
    print("WARNING: Tap block not found. May already be patched.")

if old_swipe in content:
    content = content.replace(old_swipe, new_swipe, 1)
    print("PATCHED: TouchStabilizationEngine (Swipe) - Dead code eliminated")
    patched = True
else:
    print("WARNING: Swipe block not found. May already be patched.")

if patched:
    with open(gameplay_path, 'w') as f:
        f.write(content)
    print("SUCCESS: Unused variables eliminated. Warnings resolved.")
else:
    print("NO CHANGES REQUIRED.")
