#!/usr/bin/env python3
import os
import sys

def patch_file(filepath, expected, replacement):
    if not os.path.exists(filepath):
        print(f"FAIL: File not found: {filepath}")
        sys.exit(1)
        
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
        
    if replacement in content:
        print(f"PASS (Already applied): {filepath}")
        return

    if expected not in content:
        print(f"FAIL: Expected text block not found in {filepath}")
        sys.exit(1)
        
    new_content = content.replace(expected, replacement, 1)
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(new_content)
        
    with open(filepath, 'r', encoding='utf-8') as f:
        verify_content = f.read()
        
    if replacement not in verify_content:
        print(f"FAIL: Verification failed for {filepath}")
        sys.exit(1)
        
    print(f"PASS: Successfully patched {filepath}")

def main():
    print("Executing Python 3 patch fix...")

    # First reset gameplay_engine.kt to clean state if needed
    path_gameplay_engine = "app/src/main/java/com/assistant/gameplay_engine.kt"
    with open(path_gameplay_engine, 'r', encoding='utf-8') as f:
        gp_content = f.read()

    bad_cb_1 = """            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                updateTelemetry(result = true, isCompleted = true, isCancelled = false)
                callback?.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                cancelled = true
                updateTelemetry(result = true, isCompleted = false, isCancelled = true)
                callback?.onCancelled(gestureDescription)
            }"""

    good_cb_1 = """            override fun onCompleted(gestureDescription: GestureDescription?) {
                updateTelemetry(result = true, isCompleted = true, isCancelled = false)
                callback?.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                updateTelemetry(result = true, isCompleted = false, isCancelled = true)
                callback?.onCancelled(gestureDescription)
            }"""

    if bad_cb_1 in gp_content:
        gp_content = gp_content.replace(bad_cb_1, good_cb_1, 1)
        with open(path_gameplay_engine, 'w', encoding='utf-8') as f:
            f.write(gp_content)
        print("PASS: Fixed wrappedCallback in gameplay_engine.kt")

    # 1. Patch AsynchronousGestureQueue.kt
    path_gesture_queue = "app/src/main/java/com/assistant/input/AsynchronousGestureQueue.kt"
    expected_gesture_queue = """        val builder = GestureDescription.Builder()
        // Consume strobePackets (4 packets x 4 floats: [ptr, px, py, t]) to construct multi-stroke gesture
        for (i in 0 until 4) {
            val idx = i * 4
            val px = strobePackets[idx + 1]
            val py = strobePackets[idx + 2]
            val timeOffset = strobePackets[idx + 3].toLong()
            val strokePath = Path().apply {
                moveTo(element.startX, element.startY)
                lineTo(px, py)
            }
            val strokeDuration = (duration - timeOffset).coerceAtLeast(2L)
            builder.addStroke(GestureDescription.StrokeDescription(strokePath, timeOffset, strokeDuration))
        }
        val gesture = builder.build()
        GestureExecutionAuthority.execute(element.service, gesture, null, null, origin = "AsynchronousGestureQueue")"""

    replacement_gesture_queue = """        val strobePath = Path().apply {
            moveTo(element.startX, element.startY)
            for (i in 0 until 4) {
                val idx = i * 4
                val px = strobePackets[idx + 1]
                val py = strobePackets[idx + 2]
                lineTo(px, py)
            }
            lineTo(endX, endY)
        }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(strobePath, 0L, duration.coerceAtLeast(2L)))
        val gesture = builder.build()
        GestureExecutionAuthority.execute(element.service, gesture, null, null, origin = "AsynchronousGestureQueue")"""

    patch_file(path_gesture_queue, expected_gesture_queue, replacement_gesture_queue)

    # 2. Patch mutation/native_input_strobe_amplifier.c
    path_strobe_c = "app/src/main/cpp/mutation/native_input_strobe_amplifier.c"
    expected_strobe_c = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeInjectStrobePacketsAmplifierRef("""

    replacement_strobe_c = """JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeInjectStrobePackets("""

    patch_file(path_strobe_c, expected_strobe_c, replacement_strobe_c)

    print("ALL PATCHES APPLIED AND VERIFIED SUCCESSFULLY!")

if __name__ == "__main__":
    main()

