#!/usr/bin/env python3
"""SPLD-PATCH-v4 — Fixes silent patch failure (missing markFrame) and architectural token delivery flaw."""
import os, re, sys, tempfile

MARK = "SPLD-PATCH-v4"
ROOT = os.getcwd()
OVERLAY_PATH = os.path.join(ROOT, "app", "src", "main", "java", "com", "assistant", "OverlayService.kt")
RECOVERY_PATH = os.path.join(ROOT, "app", "src", "main", "java", "com", "assistant", "SplendorCaptureRecovery.kt")

def log(state, fix, msg): print(f"[{state}] [{fix}] {msg}")

def atomic_write(path, text):
    if not os.path.exists(os.path.dirname(path)):
        os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as fh: fh.write(text)
    os.replace(tmp, path)

# 1. Fix OverlayService.kt
with open(OVERLAY_PATH, "r", encoding="utf-8") as f:
    ot = f.read()

nt = ot
changed = False

# Inject markFrame if missing (Fixes Silent Failure)
if "com.assistant.SplendorCaptureRecovery.markFrame()" not in nt:
    pattern = r"(captureFrameCount\+\+\s*\n)(\s*if \(com\.assistant\.vision\.ForegroundGate\.shouldSkipCapture\(\)\) \{)"
    replacement = r"\1            // SPLD-PATCH-v4:FRAME\n            com.assistant.SplendorCaptureRecovery.markFrame()\n\2"
    nt_new, count = re.subn(pattern, replacement, nt, count=1)
    if count > 0:
        nt = nt_new
        changed = True
        log("PROVEN", "FIX-A", "markFrame hook successfully injected (Silent failure from v2 resolved)")
    else:
        log("BLOCKED", "FIX-A", "Could not locate captureFrameCount++ anchor for markFrame injection")

# Inject applyFreshProjection method if missing (Fixes Architectural Flaw)
if "fun applyFreshProjection(code: Int, data: Intent)" not in nt:
    method_code = """
    // SPLD-PATCH-v4:TOKEN-RESTORE
    fun applyFreshProjection(code: Int, data: Intent) {
        projectionRevoked = false
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        setupMediaProjection(code, data)
        RuntimeLogger.log("AGENT CAPTURE RESTORED: Fresh MediaProjection token applied successfully", "AGENT")
    }
"""
    pattern = r"(\s*override fun onBind\(intent: Intent\?\): IBinder\? = null)"
    replacement = method_code + r"\1"
    nt_new, count = re.subn(pattern, replacement, nt, count=1)
    if count > 0:
        nt = nt_new
        changed = True
        log("PROVEN", "FIX-ARCH", "applyFreshProjection method injected for fresh token application")
    else:
        log("BLOCKED", "FIX-ARCH", "Could not locate onBind anchor for applyFreshProjection injection")

if changed:
    atomic_write(OVERLAY_PATH, nt)

# 2. Fix SplendorCaptureRecovery.kt
with open(RECOVERY_PATH, "r", encoding="utf-8") as f:
    rt = f.read()

rnt = rt
rchanged = False

# Replace deliver() method to use applyFreshProjection and consume rc/data (Fixes Token Flaw + Warnings)
if "applyFreshProjection" not in rnt:
    old_deliver = r"""    fun deliver(rc: Int, data: Intent) {
        dead = false; armed = false; lastFrame = 0L
        val svc = svcRef?.get() ?: return
        val ms = svc.javaClass.methods
        val m = ms.firstOrNull { it.name == "restartCapture" && it.parameterTypes.size == 0 }
        if (m != null) {
            try { m.invoke(svc); Log.i(TAG, "capture restarted via restartCapture") } catch (e: Exception) { Log.e(TAG, "restart failed", e) }
        } else {
            Log.w(TAG, "restartCapture method not resolvable at runtime")
        }
    }"""
    
    new_deliver = r"""    fun deliver(rc: Int, data: Intent) {
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
    
    if old_deliver in rnt:
        rnt = rnt.replace(old_deliver, new_deliver)
        rchanged = True
        log("PROVEN", "FIX-ARCH", "deliver() updated to apply fresh token and eliminate unused parameter warnings")
    else:
        log("BLOCKED", "FIX-ARCH", "Could not locate exact deliver() method signature to replace")

if rchanged:
    atomic_write(RECOVERY_PATH, rnt)

print(f"\n===== {MARK} COMPLETE =====")
print("Run: ./gradlew :app:compileDebugKotlin")
