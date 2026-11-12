package com.assistant.diagnostic

import android.content.Context
import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeLogger {

    @Volatile
    private var externalLogFile: File? = null

    @Volatile
    private var forensicDir: File? = null

    @Volatile
    private var executionChainLog: File? = null

    @Volatile
    private var telemetryLog: File? = null

    @Volatile
    private var heartbeatLog: File? = null

    @Volatile
    private var fieldTestLog: File? = null

    @Volatile
    var FIELD_TEST_MODE = true

    @Synchronized
    fun initialize(context: Context) {

        // Logging destination: /sdcard/Splendor-Assist only (SplendorStorageRoot).

        // FIX #3 & #4: Gate heavy external file creation to main process only.
        // Background processes will safely fall back to fast, process-private internal logs,
        // completely eliminating repeated external storage setup and inter-process contention.
        val isMainProcess = shouldWriteSessionHeader(context)

        if (isMainProcess) {
            if (externalLogFile == null) {
                // Canonical root only: file() throws when storage is not
                // ready; a null log file degrades to a no-op append (the
                // same behaviour background processes already have).
                externalLogFile = try { SplendorStorageRoot.file("Splendor_Field_Logs.txt") } catch (_: Throwable) { null }
            }

            if (forensicDir == null) {

                forensicDir = try { SplendorStorageRoot.subdirectory("Forensics") } catch (_: Throwable) { null }

                forensicDir?.let { dir ->
                    executionChainLog = File(dir, "execution_chain.log")
                    telemetryLog = File(dir, "telemetry.log")
                    heartbeatLog = File(dir, "heartbeat.log")
                    fieldTestLog = File(dir, "fieldtest.log")
                }
            }
        }

        // Retention: runs after forensicDir is assigned above. Before this fix it
        // ran first, while forensicDir was still null, so the sweep deleted nothing.
        reconcileExpired()

        if (!isMainProcess) {
            return
        }

        val timestamp =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US
            ).format(Date())

        writeToAll(
            "\n=== SESSION START: $timestamp ===\n"
        )

        forensic(
            "FORENSIC",
            "SESSION_START $timestamp"
        )

        log(
            "Application started",
            "BOOT"
        )
    }

    private fun shouldWriteSessionHeader(context: Context): Boolean {
        val processName = currentProcessName()
        return processName.isBlank() || processName == context.packageName
    }

    private fun currentProcessName(): String {
        return try {
            File("/proc/self/cmdline").inputStream().use { stream ->
                val bytes = stream.readBytes()
                bytes.toString(Charsets.UTF_8).trim(
                    '\u0000',
                    ' ',
                    '\n',
                    '\r',
                    '\t'
                )
            }
        } catch (_: Exception) {
            ""
        }
    }

    private const val RETENTION_MS = 10_800_000L

    /**
     * Retention sweep over the canonical external forensic directory.
     *
     * Deletes files in the canonical /sdcard/Splendor-Assist/Forensics
     * directory whose last write is older than RETENTION_MS. No-op until
     * initialize() has assigned forensicDir (main process only).
     */
    @Synchronized
    fun reconcileExpired(now: Long = System.currentTimeMillis()) {
        val dir = forensicDir ?: return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (now - file.lastModified() >= RETENTION_MS) file.delete()
        }
    }


    private val dateFormat = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    }
    private val threadLocalDate = ThreadLocal.withInitial { java.util.Date() }

    /** Caller location for field, watchdog, deathwatch and agent diagnostics. */
    private fun callerLocation(): String {
        // ART can place VMStack.java with line -2 before the source caller.
        // Skip logger internals and runtime frames, then use the first real
        // Kotlin or Java source location with a positive line number.
        val frame = Throwable().stackTrace.firstOrNull { candidate ->
            val name = candidate.className
            val file = candidate.fileName
            !name.startsWith(RuntimeLogger::class.java.name) &&
                !name.startsWith("dalvik.") &&
                !name.startsWith("java.") &&
                !name.startsWith("javax.") &&
                !name.startsWith("jdk.") &&
                !name.startsWith("sun.") &&
                candidate.lineNumber > 0 &&
                file != null && (file.endsWith(".kt") || file.endsWith(".java"))
        } ?: return "unknown:0"
        val file = frame.fileName ?: return "unknown:0"
        val className = frame.className.substringBefore('$')
        val pkg = className.substringBeforeLast('.', "").replace('.', '/')
        val root = if (className.startsWith("com.assistant.diagnostic."))
            "core/src/main/java" else "app/src/main/java"
        return "$root/$pkg/$file:${frame.lineNumber}"
    }

    @Synchronized
    fun log(
        message: String,
        tag: String
    ) {

        val date = threadLocalDate.get()!!
        date.time = System.currentTimeMillis()
        val timestamp = dateFormat.get()!!.format(date)

        val logEntry =
            "$timestamp [$tag] source=${callerLocation()} $message\n"

        writeToAll(logEntry)
        LogMirror.mirror(tag, android.util.Log.INFO, logEntry)

        if (FIELD_TEST_MODE) {
            forensic(
                tag,
                message
            )
        }
    }

    @Synchronized
    fun execution(
        stage:String,
        details:String
    ){
        LogMirror.mirror(stage, android.util.Log.INFO, details)
        append(
            executionChainLog,
            "${now()} [$stage] source=${callerLocation()} $details\n"
        )
    }

    @Synchronized
    fun telemetry(
        details:String
    ){
        append(
            telemetryLog,
            "${now()} source=${callerLocation()} $details\n"
        )
    }

    @Synchronized
    fun heartbeat(
        details:String
    ){
        append(
            heartbeatLog,
            "${now()} source=${callerLocation()} $details\n"
        )
    }

    @Synchronized
    fun forensic(
        tag:String,
        details:String
    ){
        append(
            fieldTestLog,
            "${now()} [$tag] source=${callerLocation()} $details\n"
        )
    }

    private fun now(): String {
        val date = threadLocalDate.get() ?: Date()
        date.time = System.currentTimeMillis()
        return dateFormat.get()?.format(date) ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    // FIX #4: Inter-process contention prevention.
    // Single atomic write(byteArray) via FileOutputStream with append=true.
    // Replaces buffered FileWriter to guarantee O_APPEND atomicity at the kernel level,
    // completely eliminating byte interleaving and forensic ordering problems
    // without requiring an inter-process lock that would block the gameplay thread.
    // SPLENDOR_LOG_ASYNC_FIX: Offload disk append to single background thread executor
    private val logIoExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(256),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RuntimeLogIO").apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { _, _ -> droppedWrites.incrementAndGet() }
    )
    private val droppedWrites = java.util.concurrent.atomic.AtomicLong()
    fun droppedWriteCount(): Long = droppedWrites.get()

    private fun append(
        file:File?,
        text:String
    ){
        if (file == null) return
        logIoExecutor.execute {
            try {
                java.io.FileOutputStream(file, true).use { fos ->
                    fos.write(text.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                droppedWrites.incrementAndGet()
                android.util.Log.e("RuntimeLogger", "Log append failed", e)
            }
        }
    }

    private fun writeToAll(
        text: String
    ) {
        append(externalLogFile, text)
    }
}
