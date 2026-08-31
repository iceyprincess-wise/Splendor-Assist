#!/usr/bin/env python3
import os
import re
import sys

FILE_PATH = "app/src/main/java/com/assistant/SplendorCaptureRecovery.kt"

if not os.path.exists(FILE_PATH):
    print(f"FATAL: Target file not found at {FILE_PATH}")
    sys.exit(1)

with open(FILE_PATH, 'r', encoding='utf-8') as f:
    content = f.read()

# Find the exact deliver method anchor
old_method = """    fun deliver(rc: Int, data: Intent) {
        dead = false; armed = false; lastFrame = 0L
        val svc = svcRef?.get() ?: return
        val ms = svc.javaClass.methods
        // SPLD-PATCH-v4:TOKEN-APPLY
        val m = ms.firstOrNull { it.name == "applyFreshProjection" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType && Intent::class.java.isAssignableFrom(it.parameterTypes[1]) }
        if (m != null) {
            try { m.invoke(svc, rc, data); Log.i(TAG, "capture restored via applyFreshProjection") } catch (e: Exception) { Log.e(TAG, "restore failed", e) }
        } else {
            Log.w(TAG, "applyFreshProjection method not resolvable at runtime")
        }
    }"""

new_method = """    fun deliver(rc: Int, data: Intent) {
        dead = false; armed = false; lastFrame = 0L
        val svc = OverlayService.instance
        if (svc != null) {
            try {
                svc.applyFreshProjection(rc, data)
                Log.i(TAG, "capture restored via applyFreshProjection")
            } catch (e: Exception) {
                Log.e(TAG, "restore failed", e)
            }
        } else {
            Log.w(TAG, "OverlayService instance not available for token restore")
        }
    }"""

if old_method in content:
    content = content.replace(old_method, new_method)
    print("[1/1] Replaced fragile reflection with direct type-safe call to OverlayService.instance")
else:
    print("FATAL: Could not find exact deliver method anchor. Aborting.")
    sys.exit(1)

# Final Structural Verification
cleaned = re.sub(r'".*?(?<!\\)"', '""', content)
cleaned = re.sub(r"'.'", "''", cleaned)
open_b = cleaned.count('{')
close_b = cleaned.count('}')

if open_b != close_b:
    print(f"FATAL: Brace mismatch detected! Open: {open_b}, Close: {close_b}. Aborting write.")
    sys.exit(1)

with open(FILE_PATH, 'w', encoding='utf-8') as f:
    f.write(content)

print(f"\n✅ PATCH APPLIED SUCCESSFULLY. Brace balance verified: {open_b} open, {close_b} close.")
