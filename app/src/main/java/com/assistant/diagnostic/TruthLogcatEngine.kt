package com.assistant.diagnostic

import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

object TruthLogcatEngine {
    private val running = AtomicBoolean(false)
    private var tailThread: Thread? = null
    private val truthFile: File? by lazy {
        try {
            if (SplendorStorageRoot.isReady()) {
                File(SplendorStorageRoot.directory(), "Truth_logcat.txt").also {
                    if (!it.exists()) it.createNewFile()
                }
            } else null
        } catch (_: Throwable) { null }
    }

    fun start() {
        if (running.getAndSet(true)) return
        tailThread = Thread({
            try {
                val sourceFile = File("/sdcard/Splendor-Assist", "Splendor_Field_Logs.txt")
                if (!sourceFile.exists()) sourceFile.createNewFile()
                
                var filePointer = 0L
                while (running.get()) {
                    val raf = RandomAccessFile(sourceFile, "r")
                    val fileLength = raf.length()
                    if (fileLength < filePointer) {
                        filePointer = 0L // File rotated/truncated
                    }
                    raf.seek(filePointer)
                    var line = raf.readLine()
                    while (line != null) {
                        if (line.contains("NativeBridge") || 
                            line.contains("AsynchronousGestureQueue") || 
                            line.contains("NATIVE_TRUTH") || 
                            line.contains("GESTURE") ||
                            line.contains("UnsatisfiedLinkError") ||
                            line.contains("AndroidRuntime") ||
                            line.contains("FATAL") ||
                            line.contains("fallback") ||
                            line.contains("CRITICAL") ||
                            line.contains("TruthLogcat")) {
                            writeTruth(line)
                        }
                        line = raf.readLine()
                    }
                    filePointer = raf.filePointer
                    raf.close()
                    Thread.sleep(500) // Poll every 500ms
                }
            } catch (_: Throwable) {
                running.set(false)
            }
        }, "TruthLogcat-Tail-Thread").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun stop() {
        running.set(false)
        tailThread?.interrupt()
    }

    private fun writeTruth(line: String) {
        try {
            truthFile?.let {
                java.io.FileOutputStream(it, true).use { fos ->
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (_: Throwable) {}
    }
}
