import os
import sys

file_path = "app/src/main/java/com/assistant/OverlayService.kt"

if not os.path.exists(file_path):
    print(f"❌ ERROR: {file_path} not found in repository!")
    sys.exit(1)

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

original_content = content

# ==========================================
# 1. INJECT CaptureResult ENUM
# ==========================================
capture_state_str = """    enum class CaptureState {
        IDLE,
        AUTHORIZED,
        ACTIVE,
        REVOKED,
        FAILED
    }"""

new_capture_state_str = """    enum class CaptureState {
        IDLE,
        AUTHORIZED,
        ACTIVE,
        REVOKED,
        FAILED
    }

    enum class CaptureResult { SUCCESS, BUSY, INVALID_SESSION, FAILED }"""

if capture_state_str not in content:
    print("❌ ANCHOR MISMATCH: 'CaptureState' not found or already modified!")
    sys.exit(1)
content = content.replace(capture_state_str, new_capture_state_str, 1)

# ==========================================
# 2. MODIFY recreateCaptureSurfacesInternal() SIGNATURE & EARLY EXITS
# ==========================================
old_recreate_start = """    private fun recreateCaptureSurfacesInternal() {
        if (mediaProjection == null) {
            RuntimeLogger.log("recreateCaptureSurfaces: mediaProjection is null", "OVERLAY")
            return
        }
        if (captureState != CaptureState.AUTHORIZED && captureState != CaptureState.ACTIVE) {
            RuntimeLogger.log("recreateCaptureSurfaces: invalid state $captureState", "OVERLAY")
            return
        }"""

new_recreate_start = """    private fun recreateCaptureSurfacesInternal(): CaptureResult {
        if (mediaProjection == null) {
            RuntimeLogger.log("recreateCaptureSurfaces: mediaProjection is null", "OVERLAY")
            return CaptureResult.INVALID_SESSION
        }
        if (captureState != CaptureState.AUTHORIZED && captureState != CaptureState.ACTIVE) {
            RuntimeLogger.log("recreateCaptureSurfaces: invalid state $captureState", "OVERLAY")
            return CaptureResult.INVALID_SESSION
        }"""

if old_recreate_start not in content:
    print("❌ ANCHOR MISMATCH: 'recreateCaptureSurfacesInternal start' not found!")
    sys.exit(1)
content = content.replace(old_recreate_start, new_recreate_start, 1)

# ==========================================
# 3. MODIFY recreateCaptureSurfacesInternal() FINAL VERIFICATION & RETURN
# ==========================================
old_recreate_end = """currentDpi = metrics.densityDpi

        captureState = CaptureState.ACTIVE
    }"""

new_recreate_end = """currentDpi = metrics.densityDpi

        captureState = CaptureState.ACTIVE
        
        val reader = imageReader
        val vd = virtualDisplay
        
        return if (reader != null && vd != null && vd.surface == reader.surface && captureState == CaptureState.ACTIVE) {
            CaptureResult.SUCCESS
        } else {
            CaptureResult.FAILED
        }
    }"""

if old_recreate_end not in content:
    print("❌ ANCHOR MISMATCH: 'recreateCaptureSurfacesInternal end' not found!")
    sys.exit(1)
content = content.replace(old_recreate_end, new_recreate_end, 1)

# ==========================================
# 4. MODIFY restartCapture() TO ENFORCE STRICT VERIFICATION
# ==========================================
old_restart = """            try {
                RuntimeLogger.log("AGENT CAPTURE RESTART: attempting ImageReader replacement", "AGENT")
                recreateCaptureSurfacesInternal()
                lastFrameProcessedMs = 0L
                captureFrameCount = 0L
                RuntimeLogger.log("AGENT CAPTURE RESTART: ImageReader replaced successfully", "AGENT")
                return true
            } catch (e: Exception) {"""

new_restart = """            try {
                RuntimeLogger.log("AGENT CAPTURE RESTART: attempting ImageReader replacement", "AGENT")
                val result = recreateCaptureSurfacesInternal()
                if (result != CaptureResult.SUCCESS) {
                    RuntimeLogger.log("AGENT CAPTURE RESTART FAILED: $result", "AGENT")
                    return false
                }
                lastFrameProcessedMs = 0L
                captureFrameCount = 0L
                RuntimeLogger.log("AGENT CAPTURE RESTART: ImageReader replaced successfully", "AGENT")
                return true
            } catch (e: Exception) {"""

if old_restart not in content:
    print("❌ ANCHOR MISMATCH: 'restartCapture' not found!")
    sys.exit(1)
content = content.replace(old_restart, new_restart, 1)

# ==========================================
# FINAL VALIDATION & WRITE
# ==========================================
if content == original_content:
    print("❌ FATAL: No changes were applied to the file!")
    sys.exit(1)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("✅ SUCCESS: Patch applied cleanly and safely to OverlayService.kt!")
print("✅ Task Closure Protocol: All anchors verified, no silent failures.")
