package com.assistant.diagnostic

import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

object TruthLogcatEngine {
    private val running = AtomicBoolean(false)

    // No66-D36: the truth file was a `by lazy` val that cached null FOREVER
    // when SplendorStorageRoot was not ready at first access (isReady() is
    // a pure check; the lazy never retried). A tailer started before the
    // All Files grant therefore never wrote a line for the whole process.
    // Resolve it on every write instead: directory() retries initialize()
    // at the point of use (S17-D1), so a later grant heals the writer.
    private fun resolveTruthFile(): File? {
        return try {
            File(SplendorStorageRoot.directory(), "Truth_logcat.txt").also {
                if (!it.exists()) it.createNewFile()
            }
        } catch (_: Throwable) { null }
    }

    fun start() {
        if (running.getAndSet(true)) return
        // T-12 (Phase T): stop() deleted (zero callers) and the stored
        // tailThread with it — the thread runs until process death, as
        // before. The getAndSet guard above already prevents double-start.
        Thread({
            // No66-D37: the source file is resolved inside the poll loop, not
            // once at thread start: if storage is not ready yet, the poll
            // fails, logs, and retries 500ms later instead of the thread
            // dying before its first poll. A re-resolve also re-tails from
            // the end after a rotation the length check missed.
            var sourceFile: File? = null
            var filePointer = 0L
            while (running.get()) {
                try {
                    if (sourceFile == null || !sourceFile.exists()) {
                        sourceFile = SplendorStorageRoot.file("Splendor_Field_Logs.txt")
                        if (!sourceFile.exists()) sourceFile.createNewFile()
                        filePointer = sourceFile.length()
                    }
                    val raf = RandomAccessFile(sourceFile, "r")
                    try {
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
                                line.contains("GK_DECISION") ||
                                line.contains("ENGINE FAILURE") ||
                                line.contains("Direct dispatch") ||
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
                    } finally {
                        // No66-D37: the old raf.close() sat after the inner
                        // while, so a readLine() throw leaked the descriptor
                        // AND killed the thread. Close on every path.
                        try { raf.close() } catch (_: Throwable) {}
                    }
                    Thread.sleep(500) // Poll every 500ms
                } catch (t: Throwable) {
                    // No66-D38: a stop interrupts the sleep above; that
                    // used to land here and log a scary "tail thread
                    // died" error for a clean shutdown. Exit quietly when
                    // stopped. A real poll failure while running still logs
                    // loudly (SPLENDOR_P11_D7) — but the tailer now RETRIES
                    // on the next poll instead of freezing Truth_logcat.txt
                    // forever with no restart path (T-12: stop() is deleted
                    // — zero callers — so nothing ever restarted a dead
                    // tailer; the retry below is the recovery).
                    if (!running.get()) break
                    android.util.Log.e("TruthLogcatEngine", "tail poll failed; retrying", t)
                    sourceFile = null
                    try { Thread.sleep(500) } catch (_: Throwable) { break }
                }
            }
        }, "TruthLogcat-Tail-Thread").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun writeTruth(line: String) {
        try {
            resolveTruthFile()?.let {
                java.io.FileOutputStream(it, true).use { fos ->
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (_: Throwable) {}
    }
}
