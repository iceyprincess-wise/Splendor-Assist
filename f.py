#!/usr/bin/env python3
import os
import sys

REPO_ROOT = os.path.expanduser("~/projects/Splendor-Assist")
if not os.path.exists(REPO_ROOT):
    REPO_ROOT = os.path.expanduser("~/projects/SPLENDOR-ASSIST")
if not os.path.exists(REPO_ROOT):
    print("❌ ERROR: Repository not found at ~/projects/Splendor-Assist or ~/projects/SPLENDOR-ASSIST")
    sys.exit(1)

def apply_patch(filepath, old_content, new_content):
    path = os.path.join(REPO_ROOT, filepath)
    with open(path, 'r') as f:
        content = f.read()
    if old_content not in content:
        print(f"⚠️  WARNING: Anchor not found in {filepath}. Patch may already be applied or code structure changed.")
        print("Missing anchor:")
        print(old_content[:200] + "...")
        return False
    content = content.replace(old_content, new_content)
    with open(path, 'w') as f:
        f.write(content)
    print(f"✅ [OK] Patched {filepath}")
    return True

print("🔧 APPLYING SPLENDOR-ASSIST RESIDUAL CONTAMINATION + ZERO-ALLOC FIXES...")

# 1. Fix overlay_layout.xml - hide TextView permanently to guarantee zero app-painted pixels
apply_patch("app/src/main/res/layout/overlay_layout.xml",
'''    <TextView
        android:id="@+id/overlay_status_text"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|center_horizontal"
        android:layout_marginTop="45dp"
        android:text="@string/auto_extracted_48"
        android:textColor="#00FF00"
        android:textSize="14sp"
        android:textStyle="bold"
        android:shadowColor="#000000"
        android:shadowDx="1"
        android:shadowDy="1"
        android:shadowRadius="2" />''',
'''    <TextView
        android:id="@+id/overlay_status_text"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|center_horizontal"
        android:layout_marginTop="45dp"
        android:text="@string/auto_extracted_48"
        android:textColor="#00FF00"
        android:textSize="14sp"
        android:textStyle="bold"
        android:shadowColor="#000000"
        android:shadowDx="1"
        android:shadowDy="1"
        android:shadowRadius="2"
        android:visibility="gone" />''')

# 2. Fix OverlayService.kt - remove panicIndicator and fix OCR 5s limiter
apply_patch("app/src/main/java/com/assistant/OverlayService.kt",
'''        // Add small bounded panic indicator to prevent full-screen pixel contamination
        panicIndicator = View(this).apply {
            setBackgroundColor(android.graphics.Color.RED)
            visibility = View.GONE
        }
        val indicatorParams = android.widget.FrameLayout.LayoutParams(24, 24).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = 32
            rightMargin = 32
        }
        (overlayView as? android.view.ViewGroup)?.addView(panicIndicator, indicatorParams)
        com.assistant.vision.OverlaySelfMask.publishView("panic_indicator", panicIndicator)''',
'''        // REMOVED: Panic indicator painting to guarantee zero app-owned pixels in MediaProjection capture surface''')

apply_patch("app/src/main/java/com/assistant/OverlayService.kt",
'''                            System.currentTimeMillis() - lastMatchDetectionTime >= 5000L
                        ) {
                            SmartAssistRepository.activatePanic()''',
'''                            System.currentTimeMillis() - lastMatchDetectionTime >= 5000L
                        ) {
                            lastMatchDetectionTime = System.currentTimeMillis()
                            SmartAssistRepository.activatePanic()''')

# 3. Fix gameplay_engine.kt - Blob mutation and VisionPreprocessor/NoiseFilter zero-allocation
apply_patch("app/src/main/java/com/assistant/gameplay_engine.kt",
'''    data class Blob(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val pixelCount: Int,
        val averageRed: Float,
        val averageGreen: Float,
        val averageBlue: Float
    )''',
'''    class Blob(
        var minX: Int,
        var minY: Int,
        var maxX: Int,
        var maxY: Int,
        var pixelCount: Int,
        var averageRed: Float,
        var averageGreen: Float,
        var averageBlue: Float
    )''')

apply_patch("app/src/main/java/com/assistant/gameplay_engine.kt",
'''object VisionPreprocessor {
    private const val TAG = "VisionPreprocessor"
    
    // Pre-allocate output buffer for zero-alloc JNI crossing: 8 ints per blob, max 10000 blobs
    private val nativeBlobBuffer = IntArray(80000)

    fun process(frame: FrameNormalizer.NormalizedFrame): List<ConnectedComponentEngine.Blob> {
        val blobCount = try {
            com.assistant.NativeBridge.nativeExtractBlobs(
                frame.buffer, frame.width, frame.height, frame.rowStride, frame.pixelStride, 0.50f, nativeBlobBuffer
            )
        } catch (_: Throwable) {
            -1
        }

        if (blobCount > 0) {
            val result = ArrayList<ConnectedComponentEngine.Blob>(blobCount)
            for (i in 0 until blobCount) {
                val offset = i * 8
                val count = nativeBlobBuffer[offset + 4]
                if (count > 0) {
                    result.add(ConnectedComponentEngine.Blob(
                        minX = nativeBlobBuffer[offset],
                        minY = nativeBlobBuffer[offset + 1],
                        maxX = nativeBlobBuffer[offset + 2],
                        maxY = nativeBlobBuffer[offset + 3],
                        pixelCount = count,
                        averageRed = nativeBlobBuffer[offset + 5].toFloat() / count,
                        averageGreen = nativeBlobBuffer[offset + 6].toFloat() / count,
                        averageBlue = nativeBlobBuffer[offset + 7].toFloat() / count
                    ))
                }
            }
            return result
        }

        // Fallback retained until native path is 100% live-proven across all device states
        return fallback(frame)
    }''',
'''class BlobList(internal val pool: Array<ConnectedComponentEngine.Blob>, override val size: Int) : java.util.AbstractList<ConnectedComponentEngine.Blob>() {
    override fun get(index: Int): ConnectedComponentEngine.Blob = pool[index]
}

object VisionPreprocessor {
    private const val TAG = "VisionPreprocessor"
    
    // Pre-allocate output buffer for zero-alloc JNI crossing: 8 ints per blob, max 10000 blobs
    private val nativeBlobBuffer = IntArray(80000)
    private val blobPool = Array(10000) { ConnectedComponentEngine.Blob(0, 0, 0, 0, 0, 0f, 0f, 0f) }

    fun process(frame: FrameNormalizer.NormalizedFrame): List<ConnectedComponentEngine.Blob> {
        val blobCount = try {
            com.assistant.NativeBridge.nativeExtractBlobs(
                frame.buffer, frame.width, frame.height, frame.rowStride, frame.pixelStride, 0.50f, nativeBlobBuffer
            )
        } catch (_: Throwable) {
            -1
        }

        if (blobCount > 0) {
            var actualCount = 0
            for (i in 0 until blobCount) {
                val offset = i * 8
                val count = nativeBlobBuffer[offset + 4]
                if (count > 0) {
                    val b = blobPool[actualCount]
                    b.minX = nativeBlobBuffer[offset]
                    b.minY = nativeBlobBuffer[offset + 1]
                    b.maxX = nativeBlobBuffer[offset + 2]
                    b.maxY = nativeBlobBuffer[offset + 3]
                    b.pixelCount = count
                    b.averageRed = nativeBlobBuffer[offset + 5].toFloat() / count
                    b.averageGreen = nativeBlobBuffer[offset + 6].toFloat() / count
                    b.averageBlue = nativeBlobBuffer[offset + 7].toFloat() / count
                    actualCount++
                }
            }
            return BlobList(blobPool, actualCount)
        }

        // Fallback retained until native path is 100% live-proven across all device states
        return fallback(frame)
    }''')

apply_patch("app/src/main/java/com/assistant/gameplay_engine.kt",
'''object NoiseFilter {

    fun filter(
        blobs: List<ConnectedComponentEngine.Blob>,
        minimumPixels: Int = 4
    ): List<ConnectedComponentEngine.Blob> {

        if(blobs.isEmpty()){
            return emptyList()
        }

        return blobs.filter {

            it.pixelCount >= minimumPixels &&

            (it.maxX - it.minX) >= 1 &&

            (it.maxY - it.minY) >= 1

        }

    }
}''',
'''object NoiseFilter {

    fun filter(
        blobs: List<ConnectedComponentEngine.Blob>,
        minimumPixels: Int = 4
    ): List<ConnectedComponentEngine.Blob> {

        if(blobs.isEmpty()){
            return emptyList()
        }

        if (blobs is BlobList) {
            val pool = blobs.pool
            var writeIdx = 0
            for (i in 0 until blobs.size) {
                val b = blobs[i]
                if (b.pixelCount >= minimumPixels && (b.maxX - b.minX) >= 1 && (b.maxY - b.minY) >= 1) {
                    if (writeIdx != i) {
                        val target = pool[writeIdx]
                        target.minX = b.minX
                        target.minY = b.minY
                        target.maxX = b.maxX
                        target.maxY = b.maxY
                        target.pixelCount = b.pixelCount
                        target.averageRed = b.averageRed
                        target.averageGreen = b.averageGreen
                        target.averageBlue = b.averageBlue
                    }
                    writeIdx++
                }
            }
            return BlobList(pool, writeIdx)
        }

        return blobs.filter {

            it.pixelCount >= minimumPixels &&

            (it.maxX - it.minX) >= 1 &&

            (it.maxY - it.minY) >= 1

        }

    }
}''')

print("🚀 PATCH APPLICATION COMPLETE.")
print("➡️  NEXT STEP: Run './gradlew clean assembleDebug' to verify compilation, then test in eFootball 2027.")
