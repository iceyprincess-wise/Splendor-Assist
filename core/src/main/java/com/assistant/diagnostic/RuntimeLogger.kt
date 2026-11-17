package com.assistant.diagnostic

import android.content.Context
import com.assistant.storage.SplendorStorageRoot
import java.io.File

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeLogger {

    @Volatile
    private var externalLogFile: File? = null

    // F40-D1: the process-private internal fallback. The old header claimed
    // background processes "fall back to fast, process-private internal
    // logs" - no such fallback existed anywhere: writeToAll only appended
    // externalLogFile, which stays null outside the main process, so every
    // background-process write was a silent no-op. This file makes the
    // claim true: context.filesDir needs no permission and is private to
    // this app's processes.
    @Volatile
    private var internalLogFile: File? = null

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

    // F40-D8: the session header (SESSION_START + forensic marker + BOOT
    // line) used to be re-emitted on every initialize() call, so a
    // re-initialisation duplicated the header mid-log. Once per process.
    @Volatile
    private var sessionHeaderWritten = false

    @Synchronized
    fun initialize(context: Context) {

        // Logging destination: /sdcard/Splendor-Assist only (SplendorStorageRoot).

        // FIX #3 & #4: Gate heavy external file creation to main process only.
        // Background processes log to the process-private internal file
        // (F40-D1) instead of the external one, completely eliminating
        // repeated external storage setup and inter-process contention.
        // The per-file forensic/telemetry/heartbeat/execution logs stay
        // main-process-only and no-op elsewhere; only the main log path
        // (writeToAll) has the internal fallback.
        val isMainProcess = shouldWriteSessionHeader(context)

        // Internal fallback is set for every process, before any branch
        // below, so a background process (or a main process whose external
        // storage is not ready) still has somewhere to write.
        if (internalLogFile == null) {
            internalLogFile = try { File(context.filesDir, "internal-field-log.txt") } catch (_: Throwable) { null }
        }

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
            // Background processes used to return here silently, writing
            // nothing anywhere. The session marker now lands in the
            // internal fallback file, so a background session is visible.
            if (!sessionHeaderWritten) {
                sessionHeaderWritten = true
                writeToAll(
                    "\n=== SESSION START (background process): ${sessionTimestamp()} ===\n"
                )
            }
            return
        }

        if (sessionHeaderWritten) return
        sessionHeaderWritten = true

        // F40-D2: dropped writes were counted and never surfaced anywhere
        // (droppedWriteCount() has zero callers repo-wide). The running
        // total is now stamped on the session header when non-zero, and
        // the rejection handler below logs each drop to logcat.
        val dropped = droppedWrites.get()
        val droppedNote = if (dropped > 0L) " (prior dropped writes: $dropped)" else ""
        val timestamp = sessionTimestamp()

        writeToAll(
            "\n=== SESSION START: $timestamp ===$droppedNote\n"
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

    // F40-D7: initialize() allocated a fresh SimpleDateFormat for the
    // session timestamp while the ThreadLocal formatter below already
    // exists. Reuse it.
    private fun sessionTimestamp(): String {
        val date = threadLocalDate.get()!!
        date.time = System.currentTimeMillis()
        return dateFormat.get()!!.format(date)
    }

    private fun shouldWriteSessionHeader(context: Context): Boolean {
        val processName = currentProcessName()
        // F40-D6: an unreadable /proc/self/cmdline used to return "" and
        // "" was treated as the main process, so a background process
        // whose cmdline could not be read wrote external files - the exact
        // contention this gate exists to prevent. Fail closed instead.
        if (processName.isNullOrBlank()) {
            try { android.util.Log.w("RuntimeLogger", "process name unreadable; treating this process as background") } catch (_: Throwable) {}
            return false
        }
        return processName == context.packageName
    }

    private fun currentProcessName(): String? {
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
            null
        }
    }

    private const val RETENTION_MS = 10_800_000L

    // The four live forensic logs. The retention sweep must never delete
    // these (F40-D5): on a restart after >3h idle they are all older than
    // RETENTION_MS, and deleting them would erase the previous session's
    // death-window record (execution_chain.log) mid-investigation.
    private val LIVE_FORENSIC_FILES = setOf(
        "execution_chain.log", "telemetry.log", "heartbeat.log", "fieldtest.log"
    )

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
        // F40-D5: file.delete() used to be unchecked, so a failed sweep
        // was silent, and the sweep deleted the live logs too. Skip the
        // live files, count the outcome, and report failures to logcat.
        var failed = 0
        for (file in files) {
            if (!file.isFile) continue
            if (file.name in LIVE_FORENSIC_FILES) continue
            if (now - file.lastModified() >= RETENTION_MS) {
                if (!file.delete()) {
                    failed++
                    try { android.util.Log.w("RuntimeLogger", "retention sweep could not delete ${file.name}") } catch (_: Throwable) {}
                }
            }
        }
        if (failed > 0) {
            try { android.util.Log.w("RuntimeLogger", "retention sweep: $failed file(s) could not be deleted") } catch (_: Throwable) {}
        }
    }


    private val dateFormat = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    }
    private val threadLocalDate = ThreadLocal.withInitial { java.util.Date() }

    /**
     * Core-module packages: their source lives under core/src/main/java.
     * F40-D3: the old code mapped ONLY com.assistant.diagnostic.* to the
     * core root and sent everything else to app/src/main/java - wrong for
     * every other core package. Proven callers hit by this:
     * com.assistant.events.RuntimeEvents and
     * com.assistant.runtime.GameplayEngineRegistry both call
     * RuntimeLogger.log and were given app/ paths for core files.
     * Note the remaining approximation: com.assistant.diagnostic.*,
     * com.assistant.controlroom.* and com.assistant.survival.* exist in
     * BOTH modules (e.g. app TruthLogcatEngine vs core FilterEngine), so
     * no package-prefix map can place those exactly; diagnostic keeps its
     * core mapping (the majority of its classes are core) and the other
     * two keep the app mapping.
     */
    private val CORE_PACKAGES = arrayOf(
        "com.assistant.diagnostic.",
        "com.assistant.storage.",
        "com.assistant.events.",
        "com.assistant.audit.",
        "com.assistant.execution.",
        "com.assistant.render.",
        "com.assistant.runtime."
    )

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
        val root = if (CORE_PACKAGES.any { className.startsWith(it) })
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
        // SPLENDOR_P12_A6_SYNC: the chain log is the death-window record; the
        // async queue (256 deep, daemon thread, no flush) drops its tail on a
        // kill. Chain lines write synchronously on the calling thread instead.
        appendSync(
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
        // F40-D4: the old fallback allocated a new SimpleDateFormat (and a
        // new Date) on every call when a ThreadLocal returned null - but
        // both ThreadLocals use withInitial, so get() never returns null
        // and the fallback was dead code. Direct use, no allocation.
        val date = threadLocalDate.get()!!
        date.time = System.currentTimeMillis()
        return dateFormat.get()!!.format(date)
    }

    // FIX #4: Inter-process contention prevention.
    // Single atomic write(byteArray) via FileOutputStream with append=true.
    // Replaces buffered FileWriter to guarantee O_APPEND atomicity at the kernel level,
    // completely eliminating byte interleaving and forensic ordering problems
    // without requiring an inter-process lock that would block the gameplay thread.
    // SPLENDOR_LOG_ASYNC_FIX: Offload disk append to single background thread executor
    private val droppedWrites = java.util.concurrent.atomic.AtomicLong()
    fun droppedWriteCount(): Long = droppedWrites.get()

    private val logIoExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(256),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RuntimeLogIO").apply { isDaemon = true } },
        // F40-D2: a rejected write used to be counted and never surfaced.
        // Log it to logcat with the running total (the session header also
        // carries the total - see initialize()).
        java.util.concurrent.RejectedExecutionHandler { _, _ ->
            val total = droppedWrites.incrementAndGet()
            try { android.util.Log.w("RuntimeLogger", "log write dropped (queue full); total dropped=$total") } catch (_: Throwable) {}
        }
    )

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

    private fun appendSync(
        file: File?,
        text: String
    ) {
        if (file == null) return
        try {
            java.io.FileOutputStream(file, true).use { fos ->
                fos.write(text.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            droppedWrites.incrementAndGet()
            android.util.Log.e("RuntimeLogger", "Log appendSync failed", e)
        }
    }

    private fun writeToAll(
        text: String
    ) {
        // F40-D1: fall back to the process-private internal file when the
        // external file is unavailable (background process, or external
        // storage not ready). Previously this was append(externalLogFile)
        // only, i.e. a silent no-op in exactly those cases.
        append(externalLogFile ?: internalLogFile, text)
    }
}
