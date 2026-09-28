package com.assistant.diagnostic

import android.os.Process
import com.assistant.storage.SplendorStorageRoot
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

object TruthLogcatEngine {
    private val running = AtomicBoolean(false)
    private var logcatThread: Thread? = null
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
        logcatThread = Thread({
            try {
                val process = ProcessBuilder("logcat", "--pid=" + Process.myPid(), "-v", "threadtime").start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (running.get() && reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.contains("NativeBridge") || 
                        l.contains("AsynchronousGestureQueue") || 
                        l.contains("NATIVE_TRUTH") || 
                        l.contains("GESTURE") ||
                        l.contains("UnsatisfiedLinkError") ||
                        l.contains("AndroidRuntime") ||
                        l.contains("FATAL") ||
                        l.contains("fallback")) {
                        writeTruth(l)
                    }
                }
            } catch (_: Throwable) {
                running.set(false)
            }
        }, "TruthLogcat-Thread").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun stop() {
        running.set(false)
        logcatThread?.interrupt()
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
