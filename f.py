#!/usr/bin/env python3
import os
import re
import sys

FILE_PATH = "app/src/main/java/com/assistant/OverlayService.kt"

if not os.path.exists(FILE_PATH):
    print(f"FATAL: Target file not found at {FILE_PATH}")
    sys.exit(1)

with open(FILE_PATH, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Extract the frame processing lambda to make it reusable
lambda_start_marker = "imageReader?.setOnImageAvailableListener({ reader ->"
lambda_start = content.find(lambda_start_marker)
if lambda_start == -1:
    print("FATAL: Could not find setOnImageAvailableListener lambda anchor")
    sys.exit(1)

lambda_body_start = lambda_start + len(lambda_start_marker)
brace_count = 1
in_string = False
escape = False
lambda_end = -1

for i in range(lambda_body_start, len(content)):
    c = content[i]
    if escape:
        escape = False
        continue
    if c == '\\':
        escape = True
        continue
    if c == '"':
        in_string = not in_string
    if not in_string:
        if c == '{':
            brace_count += 1
        elif c == '}':
            brace_count -= 1
            if brace_count == 0:
                lambda_end = i
                break

if lambda_end == -1:
    print("FATAL: Could not find end of lambda body")
    sys.exit(1)

lambda_body = content[lambda_body_start:lambda_end]
lambda_body_new = lambda_body.replace("return@setOnImageAvailableListener", "return@OnImageAvailableListener")

# 2. Inject state variables and the reusable listener
inject_marker = "    // SPLD-PATCH-v4:TOKEN-RESTORE"
inject_point = content.find(inject_marker)
if inject_point == -1:
    print("FATAL: Could not find injection marker")
    sys.exit(1)

new_props = f"""    private val recoveryLock = ReentrantLock()
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentDpi = 0

    private val imageAvailableListener = ImageReader.OnImageAvailableListener {{ reader ->{lambda_body_new}    }}

"""
content = content[:inject_point] + new_props + content[inject_point:]
print("[1/4] Injected recoveryLock, state variables, and imageAvailableListener")

# 3. Rewrite recreateCaptureSurfaces()
def extract_method(text, signature):
    start = text.find(signature)
    if start == -1: return None, -1, -1
    brace_count = 0
    in_string = False
    escape = False
    end = -1
    # We start counting after the first '{' of the method
    first_brace = text.find("{", start)
    for i in range(first_brace + 1, len(text)):
        c = text[i]
        if escape: escape = False; continue
        if c == '\\': escape = True; continue
        if c == '"': in_string = not in_string
        if not in_string:
            if c == '{': brace_count += 1
            elif c == '}':
                brace_count -= 1
                if brace_count == -1:
                    end = i + 1
                    break
    return text[start:end], start, end

old_rc, rc_start, rc_end = extract_method(content, "    private fun recreateCaptureSurfaces() {")
if not old_rc:
    print("FATAL: Could not extract recreateCaptureSurfaces")
    sys.exit(1)

new_rc = """    private fun recreateCaptureSurfaces() {
        if (mediaProjection == null) {
            RuntimeLogger.log("recreateCaptureSurfaces: mediaProjection is null", "OVERLAY")
            return
        }
        if (captureState != CaptureState.AUTHORIZED && captureState != CaptureState.ACTIVE) {
            RuntimeLogger.log("recreateCaptureSurfaces: invalid state $captureState", "OVERLAY")
            return
        }

        if (!recoveryLock.tryLock()) {
            RuntimeLogger.log("recreateCaptureSurfaces: recovery already in progress", "OVERLAY")
            return
        }

        try {
            val scale = 0.4f
            val metrics = DisplayMetrics()
            val finalWidth: Int
            val finalHeight: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                finalWidth = (bounds.width() * scale).toInt() and 0xFFFFFFFE.toInt()
                finalHeight = (bounds.height() * scale).toInt() and 0xFFFFFFFE.toInt()
                metrics.densityDpi = resources.configuration.densityDpi
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                finalWidth = (metrics.widthPixels * scale).toInt() and 0xFFFFFFFE.toInt()
                finalHeight = (metrics.heightPixels * scale).toInt() and 0xFFFFFFFE.toInt()
            }

            com.assistant.vision.OverlaySelfMask.setCaptureScale(finalWidth, finalHeight, if (scale > 0f) (finalWidth / scale).toInt() else finalWidth, if (scale > 0f) (finalHeight / scale).toInt() else finalHeight)

            val isInitial = virtualDisplay == null
            val dimensionsChanged = finalWidth != currentWidth || finalHeight != currentHeight || metrics.densityDpi != currentDpi

            if (isInitial) {
                imageReader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
                imageReader?.setOnImageAvailableListener(imageAvailableListener, ocrIoHandler ?: Handler(Looper.getMainLooper()))
                virtualDisplay = mediaProjection?.createVirtualDisplay("HybridCoachScreen", finalWidth, finalHeight, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, imageReader?.surface, null, null)
            } else {
                val oldReader = imageReader
                val newReader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
                newReader.setOnImageAvailableListener(imageAvailableListener, ocrIoHandler ?: Handler(Looper.getMainLooper()))
                
                imageReader = newReader
                
                if (dimensionsChanged) {
                    virtualDisplay?.resize(finalWidth, finalHeight, metrics.densityDpi)
                }
                virtualDisplay?.setSurface(newReader.surface)
                
                try { oldReader?.close() } catch (_: Throwable) {}
                RuntimeLogger.log("recreateCaptureSurfaces: ImageReader replaced via setSurface (resize=$dimensionsChanged)", "OVERLAY")
            }
            
            currentWidth = finalWidth
            currentHeight = finalHeight
            currentDpi = metrics.densityDpi

            captureState = CaptureState.ACTIVE
        } catch (e: Exception) {
            RuntimeLogger.log("recreateCaptureSurfaces failed: ${e.message}", "OVERLAY")
        } finally {
            recoveryLock.unlock()
        }
    }"""

content = content[:rc_start] + new_rc + content[rc_end:]
print("[2/4] Rewrote recreateCaptureSurfaces with ImageReader replacement and VirtualDisplay.setSurface/resize")

# 4. Update restartCapture() to remove destructive teardown
old_restart, restart_start, restart_end = extract_method(content, "    fun restartCapture(): Boolean {")
if not old_restart:
    print("FATAL: Could not extract restartCapture")
    sys.exit(1)

new_restart = """    fun restartCapture(): Boolean {
        if (isProjectionRevoked) {
            RuntimeLogger.log("AGENT CAPTURE RESTART: projection already revoked; fresh MediaProjection authorization required", "AGENT")
            return false
        }
        if (mediaProjection == null) {
            RuntimeLogger.log("AGENT CAPTURE RESTART: no active MediaProjection", "AGENT")
            return false
        }
        try {
            RuntimeLogger.log("AGENT CAPTURE RESTART: attempting ImageReader replacement", "AGENT")
            try {
                val drainLatch = java.util.concurrent.CountDownLatch(1)
                ocrIoHandler?.post { drainLatch.countDown() } ?: drainLatch.countDown()
                drainLatch.await(100L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}

            recreateCaptureSurfaces()
            lastFrameProcessedMs = 0L
            captureFrameCount = 0L
            RuntimeLogger.log("AGENT CAPTURE RESTART: ImageReader replaced successfully", "AGENT")
            return true
        } catch (e: Exception) {
            RuntimeLogger.log("AGENT CAPTURE RESTART FAILED: ${e.message}", "AGENT")
            return false
        }
    }"""

content = content[:restart_start] + new_restart + content[restart_end:]
print("[3/4] Updated restartCapture to use non-destructive replacement")

# 5. Update startCaptureKeepAlive()
old_ka, ka_start, ka_end = extract_method(content, "    private fun startCaptureKeepAlive() {")
if not old_ka:
    print("FATAL: Could not extract startCaptureKeepAlive")
    sys.exit(1)

new_ka = """    private fun startCaptureKeepAlive() {
        keepAliveRunnable?.let { keepAliveHandler.removeCallbacks(it) }
        keepAliveRunnable = object : Runnable {
            override fun run() {
                if (!isProjectionRevoked && mediaProjection != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameProcessedMs > 10000L && lastFrameProcessedMs > 0L) {
                        RuntimeLogger.log("KEEPALIVE: Capture stale for >10s. Proactively replacing ImageReader to prevent HyperOS silent kill.", "OVERLAY")
                        try {
                            recreateCaptureSurfaces()
                            lastFrameProcessedMs = System.currentTimeMillis()
                        } catch (e: Exception) {
                            RuntimeLogger.log("KEEPALIVE replace failed: ${e.message}", "OVERLAY")
                        }
                    }
                }
                keepAliveHandler.postDelayed(this, 45000L)
            }
        }
        keepAliveHandler.postDelayed(keepAliveRunnable!!, 45000L)
    }"""

content = content[:ka_start] + new_ka + content[ka_end:]
print("[4/4] Updated startCaptureKeepAlive to use non-destructive replacement")

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
