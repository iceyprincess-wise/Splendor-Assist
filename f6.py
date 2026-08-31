#!/usr/bin/env python3
"""SPLD-PATCH-v6 (BULLETPROOF REGEX) — Eliminates silent patch failures via regex matching immune to line endings and indentation."""
import os, sys, tempfile, re

MARK = "SPLD-PATCH-v6"
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

def verify_file(path, expected_string):
    if not os.path.exists(path): return False
    with open(path, "r", encoding="utf-8") as f:
        return expected_string in f.read()

# 1. Fix OverlayService.kt
with open(OVERLAY_PATH, "r", encoding="utf-8") as f:
    ot = f.read()

nt = ot
changed = False

# Inject markFrame if missing (Regex immune to \r\n and indentation)
if "com.assistant.SplendorCaptureRecovery.markFrame()" not in nt:
    pattern1 = r"(captureFrameCount\+\+\s*\n)(\s*if \(com\.assistant\.vision\.ForegroundGate\.shouldSkipCapture\(\)\) \{)"
    replacement1 = r"\1            // SPLD-PATCH-v6:FRAME\n            com.assistant.SplendorCaptureRecovery.markFrame()\n\2"
    
    nt_new, count = re.subn(pattern1, replacement1, nt, count=1)
    if count > 0:
        nt = nt_new
        changed = True
        log("PROVEN", "FIX-A", "markFrame hook successfully injected via regex")
    else:
        log("BLOCKED", "FIX-A", "CRITICAL: Regex anchor for markFrame not found. Aborting to prevent silent failure.")
        sys.exit(1)

# Inject applyFreshProjection method if missing
if "fun applyFreshProjection(code: Int, data: Intent)" not in nt:
    pattern2 = r"(override fun onBind\(intent: Intent\?\): IBinder\? = null\s*\n)(\s*fun restartCapture\(\): Boolean \{)"
    replacement2 = r"\1\n    // SPLD-PATCH-v6:TOKEN-RESTORE\n    fun applyFreshProjection(code: Int, data: Intent) {\n        projectionRevoked = false\n        try { virtualDisplay?.release() } catch (_: Throwable) {}\n        try { imageReader?.close() } catch (_: Throwable) {}\n        virtualDisplay = null\n        imageReader = null\n        setupMediaProjection(code, data)\n        RuntimeLogger.log(\"AGENT CAPTURE RESTORED: Fresh MediaProjection token applied successfully\", \"AGENT\")\n    }\n\n\2"
    
    nt_new, count = re.subn(pattern2, replacement2, nt, count=1)
    if count > 0:
        nt = nt_new
        changed = True
        log("PROVEN", "FIX-ARCH", "applyFreshProjection method successfully injected via regex")
    else:
        log("BLOCKED", "FIX-ARCH", "CRITICAL: Regex anchor for applyFreshProjection not found. Aborting.")
        sys.exit(1)

if changed:
    atomic_write(OVERLAY_PATH, nt)
    # POST-WRITE VERIFICATION
    if not verify_file(OVERLAY_PATH, "com.assistant.SplendorCaptureRecovery.markFrame()"):
        log("BLOCKED", "VERIFY", "CRITICAL: Post-write verification failed for markFrame. File was not saved.")
        sys.exit(1)
    if not verify_file(OVERLAY_PATH, "fun applyFreshProjection(code: Int, data: Intent)"):
        log("BLOCKED", "VERIFY", "CRITICAL: Post-write verification failed for applyFreshProjection. File was not saved.")
        sys.exit(1)
    log("PROVEN", "VERIFY", "OverlayService.kt post-write verification PASSED")

# 2. Fix SplendorCaptureRecovery.kt
with open(RECOVERY_PATH, "r", encoding="utf-8") as f:
    rt = f.read()

rnt = rt
rchanged = False

if "applyFreshProjection" not in rnt:
    # Regex to match the entire deliver function body, immune to line endings
    pattern3 = r"fun deliver\(rc: Int, data: Intent\) \{[\s\S]*?restartCapture method not resolvable at runtime[\s\S]*?\}"
    replacement3 = """fun deliver(rc: Int, data: Intent) {
        dead = false; armed = false; lastFrame = 0L
        val svc = svcRef?.get() ?: return
        val ms = svc.javaClass.methods
        // SPLD-PATCH-v6:TOKEN-APPLY
        val m = ms.firstOrNull { it.name == "applyFreshProjection" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType && Intent::class.java.isAssignableFrom(it.parameterTypes[1]) }
        if (m != null) {
            try { m.invoke(svc, rc, data); Log.i(TAG, "capture restored via applyFreshProjection") } catch (e: Exception) { Log.e(TAG, "restore failed", e) }
        } else {
            Log.w(TAG, "applyFreshProjection method not resolvable at runtime")
        }
    }"""
    
    rnt_new, count = re.subn(pattern3, replacement3, rnt, count=1)
    if count > 0:
        rnt = rnt_new
        rchanged = True
        log("PROVEN", "FIX-ARCH", "deliver() updated to apply fresh token via regex")
    else:
        log("BLOCKED", "FIX-ARCH", "CRITICAL: Regex for deliver() not found. Aborting.")
        sys.exit(1)
elif "applyFreshProjection" in rnt and "restartCapture method not resolvable" not in rnt:
    log("PROVEN", "IDEMPOTENT", "deliver() already updated")
else:
    log("BLOCKED", "FIX-ARCH", "CRITICAL: File state is ambiguous. Aborting.")
    sys.exit(1)

if rchanged:
    atomic_write(RECOVERY_PATH, rnt)
    # POST-WRITE VERIFICATION
    if not verify_file(RECOVERY_PATH, "applyFreshProjection"):
        log("BLOCKED", "VERIFY", "CRITICAL: Post-write verification failed for deliver(). File was not saved.")
        sys.exit(1)
    log("PROVEN", "VERIFY", "SplendorCaptureRecovery.kt post-write verification PASSED")

print(f"\n===== {MARK} COMPLETE =====")
print("Run: ./gradlew :app:compileDebugKotlin")
print("IMPORTANT: Delete f6.py before committing: rm -rf f6.py")
