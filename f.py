#!/usr/bin/env python3
import sys
import os

def patch_overlay_races():
    path = os.path.join("app", "src", "main", "java", "com", "assistant", "OverlayService.kt")
    if not os.path.exists(path):
        print(f"FAIL: {path} not found")
        sys.exit(1)

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    # 1. Remove the flawed 500ms vision watchdog
    old_watchdog = """        if (startVision) {
            visionStartTimeMs = System.currentTimeMillis()
            visionScope.launch {
                kotlinx.coroutines.delay(500)
                if (visionInFlight.get() && System.currentTimeMillis() - visionStartTimeMs > 500L) {
                    visionInFlight.set(false)
                    try { com.assistant.diagnostic.RuntimeLogger.log("VISION_WATCHDOG_RESET: Coroutine death detected, force-unblocking frame pump.", "FAULT") } catch (_: Throwable) {}
                }
            }
        }"""

    new_watchdog = """        // REMOVED: 500ms Watchdog caused data-race by unblocking frame pump while coroutine was still reading reusableVisionBuffer"""

    # 2. Fix OCR to use immutable snapshot with proper lifecycle
    old_ocr = """    private fun processBitmapForOCR() {
        if (reusableBitmap == null || reusableBitmap!!.isRecycled) return
        if (taskExecutionLock.tryLock()) {
            try {
                recognizer.process(InputImage.fromBitmap(reusableBitmap!!, 0))
                    .addOnSuccessListener { visionText ->
                        val detectedText = visionText.textBlocks.asSequence()
                            .filterNot { com.assistant.vision.OverlaySelfMask.isSelfDrawnCapture(it.boundingBox) }
                            .joinToString("") { it.text }
                            .replace("\n", "")
                            .take(120)

                        com.assistant.vision.OverlaySelfMask.tickAndLog()

                        if (detectedText.isNotBlank()) {
                            RuntimeMetricsRegistry.ocrDetections.incrementAndGet()
                            RuntimeLogger.log("OCR: $detectedText", "OCR")
                        }
                    }
            } finally {
                taskExecutionLock.unlock()
            }
        }
    }"""

    new_ocr = """    private fun processBitmapForOCR() {
        if (reusableBitmap == null || reusableBitmap!!.isRecycled) return
        if (taskExecutionLock.tryLock()) {
            var snapshot: Bitmap? = null
            try {
                // FIX: Create immutable snapshot for async native OCR pipeline
                snapshot = reusableBitmap!!.copy(reusableBitmap!!.config, false)
                val snapshotForClosure = snapshot
                
                recognizer.process(InputImage.fromBitmap(snapshot, 0))
                    .addOnCompleteListener { task ->
                        // Recycle snapshot ONLY after native OCR threads are completely finished
                        try { snapshotForClosure.recycle() } catch (_: Throwable) {}
                        
                        if (task.isSuccessful) {
                            val visionText = task.result
                            val detectedText = visionText.textBlocks.asSequence()
                                .filterNot { com.assistant.vision.OverlaySelfMask.isSelfDrawnCapture(it.boundingBox) }
                                .joinToString("") { it.text }
                                .replace("\n", "")
                                .take(120)

                            com.assistant.vision.OverlaySelfMask.tickAndLog()

                            if (detectedText.isNotBlank()) {
                                RuntimeMetricsRegistry.ocrDetections.incrementAndGet()
                                RuntimeLogger.log("OCR: $detectedText", "OCR")
                            }
                        }
                    }
            } catch (t: Throwable) {
                snapshot?.recycle()
            } finally {
                taskExecutionLock.unlock()
            }
        }
    }"""

    if old_watchdog in content and old_ocr in content:
        content = content.replace(old_watchdog, new_watchdog, 1)
        content = content.replace(old_ocr, new_ocr, 1)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        print("PASS: OverlayService.kt OCR snapshot lifecycle and watchdog race fixed.")
    else:
        print("FAIL: Pattern not matched in OverlayService.kt")
        sys.exit(1)

if __name__ == "__main__":
    patch_overlay_races()
    print("PASS: All structural repairs applied.")
