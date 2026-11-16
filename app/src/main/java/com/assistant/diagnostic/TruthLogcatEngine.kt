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
                val sourceFile = SplendorStorageRoot.file("Splendor_Field_Logs.txt")
                if (!sourceFile.exists()) sourceFile.createNewFile()
                
                var filePointer = sourceFile.length()
                while (running.get()) {
                    val raf = RandomAccessFile(sourceFile, "r")
                    val fileLength = raf.length()
                    if (fileLength < filePointer) {
                        filePointer = 0L // File rotated/truncated
                    }
                    raf.seek(filePointer)
                    // F37-D11: readLine() decodes bytes as Latin-1, so a
                    // UTF-8 em-dash became 3 chars and Truth lines ran long.
                    // Round-trip through Latin-1 bytes to restore UTF-8.
                    var line = raf.readLine()?.let { String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }
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
                        line = raf.readLine()?.let { String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }
                    }
                    filePointer = raf.filePointer
                    raf.close()
                    Thread.sleep(500) // Poll every 500ms
                }
            } catch (t: Throwable) {
                // SPLENDOR_P11_D7: was a silent death — if the field-log file
                // became unreadable, the tailer stopped with no log and
                // Truth_logcat.txt froze while the process lived.
                android.util.Log.e("TruthLogcatEngine", "tail thread died; Truth_logcat.txt will freeze", t)
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
