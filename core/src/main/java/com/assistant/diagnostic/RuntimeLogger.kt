package com.assistant.diagnostic

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

        // LOGGAPS: app version for the session header (v268 log pass:
        // no version string exists in any log, so a session could not
        // be tied to a build). Read here so both the main and the
        // background headers carry it.
        val (versionName, versionCode) = appVersion(context)

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
                    "\n=== SESSION START (background process): ${sessionTimestamp()} | versionName=$versionName versionCode=$versionCode ===\n"
                )
            }
            return
        }

        if (sessionHeaderWritten) return
        sessionHeaderWritten = true

        // F40-D2: dropped writes were counted and never surfaced anywhere.
        // The running total is now stamped on the session header when
        // non-zero, and the rejection handler below logs each drop to
        // logcat. (R-8, Phase R: the "zero callers" claim is stale —
        // droppedWriteCount() has a live caller, DashboardInjector:259,
        // wired in Phase I I-5.)
        val dropped = droppedWrites.get()
        val droppedNote = if (dropped > 0L) " (prior dropped writes: $dropped)" else ""
        val timestamp = sessionTimestamp()

        writeToAll(
            "\n=== SESSION START: $timestamp | versionName=$versionName versionCode=$versionCode ===$droppedNote\n"
        )

        forensic(
            "FORENSIC",
            "SESSION_START $timestamp versionCode=$versionCode versionName=$versionName"
        )

        log(
            "Application started",
            "BOOT"
        )
    }

    // F40-D7: initialize() allocated a fresh SimpleDateFormat for the
    // session timestamp while the ThreadLocal formatter below already
    // exists. Reuse it.
    // LOGGAPS: installed version via PackageManager (UpdateChecker
    // precedent: installedVersionCode). Fail-open to unknown/-1.
    private fun appVersion(context: Context): Pair<String, Long> {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            Pair(info.versionName ?: "unknown", code)
        } catch (_: Throwable) {
            Pair("unknown", -1L)
        }
    }

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
        // O37-3 (batch 5): the four live logs were skipped by the age
        // sweep forever, so they grew without bound. Rotate each one at
        // startup when it passes the cap (same 8 MB cap and single .old
        // generation as the field log): the previous session's
        // death-window record survives one restart in the .old file,
        // which is when it is investigated, and the live file never
        // grows past the cap. Runs at initialize(), before the session
        // appends anything, so no append races the rename.
        rotateLiveForensicLogs(dir)
    }

    // O37-3 (batch 5): same cap and single-generation pattern as
    // rotateFieldLogIfOversize. Best-effort: a failed rotate never loses
    // a line — the session just keeps appending to the oversize file
    // until the next startup.
    private fun rotateLiveForensicLogs(dir: File) {
        for (name in LIVE_FORENSIC_FILES) {
            try {
                val file = File(dir, name)
                if (!file.exists() || file.length() <= FIELD_LOG_MAX_BYTES) continue
                val old = File(dir, "$name.old")
                if (old.exists()) old.delete()
                if (!file.renameTo(old)) {
                    try { android.util.Log.w("RuntimeLogger", "live forensic rotation failed for $name") } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                try { android.util.Log.w("RuntimeLogger", "live forensic rotation failed for $name") } catch (_: Throwable) {}
            }
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
     *
     * no59 ITEM 2: the location is now SHORT — "<module>/<File>.<ext>:<line>"
     * (LogFormat.shortLocation) — and is printed ONCE per location run,
     * not on every line (LogFormat.Grouper, one per destination stream
     * below). The frame-skipping, the positive-line-number guard and
     * this core-package map are unchanged: only the rendering changed.
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

    // no59 ITEM 2: one Grouper per destination stream. Each stream
    // re-headers independently (see LogFormat): a location change in the
    // main field log never forces a header in the telemetry log, and
    // vice versa. All five are touched only under this object's
    // @Synchronized monitor, except mainGrouper.reset() from the
    // rotation check on the RuntimeLogIO thread (see rotateFieldLogIfOversize).
    private val mainGrouper = LogFormat.Grouper()
    private val executionGrouper = LogFormat.Grouper()
    private val telemetryGrouper = LogFormat.Grouper()
    private val heartbeatGrouper = LogFormat.Grouper()
    private val forensicGrouper = LogFormat.Grouper()

    // no59 ITEM 4 (file-logging load): Splendor_Field_Logs.txt used to
    // grow WITHOUT BOUND — every RuntimeLogger.log() appended forever
    // (88 caller files repo-wide) and nothing ever rotated it. LogMirror
    // (8MB) and FilterEngine (1MB) already rotate; the field log is now
    // capped the same way. TruthLogcatEngine's tailer already handles a
    // shrunk/rotated source file (it resets its pointer to 0), so a
    // rotation mid-session does not freeze Truth_logcat.txt.
    private const val FIELD_LOG_MAX_BYTES = 8L * 1024L * 1024L

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
        // no59 ITEM 2: short "<module>/<File>.<ext>:<line>" — the package
        // path is dropped; only the module (core/app) and the file name
        // survive. The callerLocation() contract (first real frame,
        // positive line, "unknown:0" fallback) is unchanged.
        return LogFormat.shortLocation(className, file, frame.lineNumber, CORE_PACKAGES)
    }

    @Synchronized
    fun log(
        message: String,
        tag: String
    ) {

        val date = threadLocalDate.get()!!
        date.time = System.currentTimeMillis()
        val timestamp = dateFormat.get()!!.format(date)

        // no59 ITEM 2: grouped short-location format. The header
        // "<timestamp>: <location>:" is printed only when the location
        // changed since the last main-log line; a repeat prints just
        // "[$tag] $message". The same grouped entry goes to LogMirror,
        // whose enqueue escapes the "\n" to a literal "\n" (one mirror
        // line per entry, header included when present).
        val logEntry = mainGrouper.format(timestamp, callerLocation(), "[$tag] $message\n")

        // P2: urgent lines flush to disk immediately, never batched.
        writeToAll(logEntry, isUrgentLog(tag, message))
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
        // no59 ITEM 2: grouped short-location format (own Grouper: the
        // chain log is its own stream — see the Grouper comment above).
        appendSync(
            executionChainLog,
            executionGrouper.format(now(), callerLocation(), "[$stage] $details\n")
        )
    }

    @Synchronized
    fun telemetry(
        details:String
    ){
        // no59 ITEM 2: grouped short-location format (own Grouper).
        append(
            telemetryLog,
            telemetryGrouper.format(now(), callerLocation(), "$details\n")
        )
    }

    @Synchronized
    fun heartbeat(
        details:String
    ){
        // no59 ITEM 2: grouped short-location format (own Grouper).
        append(
            heartbeatLog,
            heartbeatGrouper.format(now(), callerLocation(), "$details\n")
        )
    }

    @Synchronized
    fun forensic(
        tag:String,
        details:String
    ){
        // no59 ITEM 2: grouped short-location format (own Grouper).
        append(
            fieldTestLog,
            forensicGrouper.format(now(), callerLocation(), "[$tag] $details\n")
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

    /**
     * P2: lines that must reach disk immediately, never batched — the
     * heal-agent lines, and any line naming a CRITICAL/FATAL condition
     * (TruthLogcatEngine tails the field log for exactly those lines, so
     * a batched CRITICAL line would arrive up to 1 s late). Everything
     * else batches under LogBatcher.
     */
    internal fun isUrgentLog(tag: String, message: String): Boolean =
        tag == "AGENT" || tag == "HEAL" || message.contains("CRITICAL") || message.contains("FATAL")

    private fun append(
        file:File?,
        text:String,
        urgent:Boolean = false
    ){
        if (file == null) return
        logIoExecutor.execute {
            try {
                // no59 ITEM 4: rotate the field log (external main log and
                // the process-private internal fallback) before it grows
                // without bound. Forensic/telemetry/heartbeat files never
                // reach here as the field log (identity check), and the
                // retention sweep owns their lifecycle instead.
                // P2: push any batched bytes to the old file before a
                // rotation renames it, then batch this line.
                if (file === externalLogFile || file === internalLogFile) {
                    try { LogBatcher.flush(file) } catch (_: Throwable) {}
                    rotateFieldLogIfOversize(file)
                }
                LogBatcher.append(file, text, urgent)
            } catch (e: Exception) {
                droppedWrites.incrementAndGet()
                android.util.Log.e("RuntimeLogger", "Log append failed", e)
            }
        }
    }

    // Runs on the single RuntimeLogIO thread, so no two appends can race
    // the rename. Best-effort: a failed rotate never loses the line —
    // the append below still runs against the oversize file.
    private fun rotateFieldLogIfOversize(file: File) {
        try {
            if (!file.exists() || file.length() <= FIELD_LOG_MAX_BYTES) return
            val oldName = if (file.name == "Splendor_Field_Logs.txt") "Splendor_Field_Logs.old.txt"
                else "internal-field-log.old.txt"
            val old = File(file.parentFile, oldName)
            if (old.exists()) old.delete()
            if (file.renameTo(old)) {
                // The new file starts clean: the next line re-headers
                // instead of continuing a run from the rotated-away file.
                mainGrouper.reset()
            }
        } catch (_: Throwable) {
            try { android.util.Log.w("RuntimeLogger", "field log rotation failed for ${file.name}") } catch (_: Throwable) {}
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
        text: String,
        urgent: Boolean = false
    ) {
        // F40-D1: fall back to the process-private internal file when the
        // external file is unavailable (background process, or external
        // storage not ready). Previously this was append(externalLogFile)
        // only, i.e. a silent no-op in exactly those cases.
        append(externalLogFile ?: internalLogFile, text, urgent)
    }
}
