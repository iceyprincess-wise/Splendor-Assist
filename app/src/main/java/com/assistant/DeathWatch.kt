package com.assistant

import com.assistant.storage.SplendorStorageRoot

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.app.ApplicationExitInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DEATH WATCH
 *
 * An UncaughtExceptionHandler only sees thrown Java/Kotlin exceptions. It is
 * structurally blind to the deaths that actually kill this app:
 *
 *   - low-memory kill (SIGKILL from LMK)
 *   - native crash (SIGSEGV)
 *   - ANR kill
 *   - user force-stop
 *
 * In all of those the process vanishes before any Kotlin code can run.
 *
 * DeathWatch works the other way round: while a process is alive it holds a
 * marker file and refreshes it with a heartbeat. A clean exit deletes it.
 * If the marker is still there on the next start, the previous session was
 * KILLED, and the last heartbeat tells us when and under what memory pressure.
 */
object DeathWatch {

    private const val REPORT_NAME = "Splendor_Crash_Reports.txt"
    
    // UPGRADE: 5000L -> 15000L. On the Helio G81-Ultra (4GB RAM), 5s heartbeats 
    // cause excessive Binder IPC to ActivityManager and Disk I/O. 15s is plenty 
    // accurate to detect LMK/ANR/SIGSEGV while drastically reducing CPU wakeups 
    // and GC pressure during eFootball 2027 15fps/30fps gameplay.
    private const val HEARTBEAT_MS = 15000L

    @Volatile private var installed = false
    @Volatile private var appContext: Context? = null
    @Volatile private var marker: File? = null
    @Volatile private var procName = "?"
    @Volatile private var startedMs = 0L
    
    // UPGRADE: Cache the static parts of the heartbeat string to prevent garbage collection.
    @Volatile private var beatPrefix = ""

    @JvmStatic
    fun install(ctx: Context) {
        if (installed) return

        val c = ctx.applicationContext

        if (!SplendorStorageRoot.isReady()) {
            log("DeathWatch not armed: canonical storage is not ready")
            return
        }

        installed = true
        appContext = c
        procName = resolveProcessName(c)
        startedMs = System.currentTimeMillis()
        
        val pidStr = android.os.Process.myPid().toString()
        beatPrefix = "$procName|$pidStr|$startedMs|"

        val dir = SplendorStorageRoot.subdirectory("deathwatch")
        val m = File(dir, safeName(procName) + ".marker")

        // previous session never removed its marker -> it was killed
        if (m.exists()) {
            try { reportDeath(m.readText()) } catch (t: Throwable) { android.util.Log.e("DeathWatch", "previous death marker unreadable, death report lost", t) }
        }

        marker = m
        beat(c, "START")

        // orderly VM exit removes the marker; SIGKILL cannot
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                try { m.delete() } catch (_: Throwable) { }
            })
        } catch (_: Throwable) { }

        val t = Thread {
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                    beat(c, "ALIVE")
                } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "deathwatch"
        try { t.start() } catch (t: Throwable) { android.util.Log.e("DeathWatch", "watcher thread failed to start, DeathWatch NOT armed", t); return }

        log("DeathWatch armed proc=" + procName + " pid=" + pidStr)
    }

    /** call on an intentional shutdown so it is not reported as a kill */
    @JvmStatic
    fun markCleanExit() {
        try { marker?.delete() } catch (_: Throwable) { }
    }

    // ---------------- heartbeat ----------------

    private fun beat(c: Context, state: String) {
        val m = marker ?: return
        try {
            val memStr = memory(c)
            // UPGRADE: Use cached prefix and US_ASCII to skip UTF-8 encoding overhead.
            val text = "$state|$beatPrefix${System.currentTimeMillis()}|$memStr"
            
            FileOutputStream(m, false).use { fos ->
                fos.write(text.toByteArray(Charsets.US_ASCII))
            }
        } catch (_: Throwable) { }
    }

    /** returns "availMB|lowMemory|thresholdMB" */
    private fun memory(c: Context): String = try {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val avail = (info.availMem / 1048576L).toString()
        val thresh = (info.threshold / 1048576L).toString()
        "$avail|${info.lowMemory}|$thresh"
    } catch (_: Throwable) { "?|?|?" }

    // ---------------- reporting ----------------

    private fun reportDeath(raw: String) {
        // SPLENDOR_P10_DW_FRESH_DETECTED: detection time captured once; it stamps
        // the Readable header and the staleness guard downstream.
        val detectedMs = System.currentTimeMillis()
        val p = raw.split("|")
        fun at(i: Int): String = if (i < p.size) p[i] else "?"

        val deadProc  = at(1)
        val deadPid   = at(2)
        val began     = at(3).toLongOrNull() ?: 0L
        val lastBeat  = at(4).toLongOrNull() ?: 0L
        val availMb   = at(5)
        val lowMem    = at(6)
        val threshMb  = at(7)

        val lived = if (began > 0 && lastBeat > began) (lastBeat - began) / 1000L else -1L
        val gap   = if (lastBeat > 0) (System.currentTimeMillis() - lastBeat) / 1000L else -1L

        val javaCrash = javaCrashMarkerPresent(began)
        val avail = availMb.toIntOrNull() ?: -1
        val thresh = threshMb.toIntOrNull() ?: 0

        // SPLENDOR_P9_DW_VERDICT: the OS exit record is ground truth and
        // must be consulted BEFORE the memory heuristic. Previously the
        // avail-range branch fired first, so a SIGSEGV kill (status 11)
        // was mislabelled LIKELY LMK, and a REASON_LOW_MEMORY death with
        // high avail fell through to EARLY DEATH. The LOW_MEMORY branch
        // keeps the exact old wording, so a corroborated LMK verdict
        // (e.g. 313MB, REASON_LOW_MEMORY) is byte-identical.
        // SPLENDOR_P10_DW_FRESH_CALL: detectedMs flows to the exit-record lookup.
        val (osExitReason, nativeCrashDetails, exitInfo) = getHistoricalExitReasonDetails(
            deadPid.toIntOrNull() ?: 0, deadProc, began, lastBeat, availMb, threshMb, lowMem, detectedMs
        )
        val osReason = exitInfo?.reason
        val osStatus = exitInfo?.status
        val verdict = when {
            javaCrash ->
                "JAVA EXCEPTION - a crash report exists for this session ($osExitReason)"
            osReason == ApplicationExitInfo.REASON_CRASH_NATIVE ->
                "NATIVE CRASH - native crash (SIGSEGV/SIGABRT) [status=" + osStatus + "] ($osExitReason)"
            osReason == ApplicationExitInfo.REASON_SIGNALED && osStatus == 11 ->
                "NATIVE CRASH - killed by OS signal 11 (SIGSEGV) ($osExitReason)"
            osReason == ApplicationExitInfo.REASON_SIGNALED ->
                "SIGNAL KILL - killed by OS signal " + osStatus + " ($osExitReason)"
            osReason == ApplicationExitInfo.REASON_LOW_MEMORY ->
                "LIKELY LMK - " + availMb + "MB free vs " + threshMb + "MB threshold ($osExitReason)"
            lowMem == "true" ->
                "LOW MEMORY KILL - system reported lowMemory at " + availMb + "MB (threshold " + threshMb + "MB) ($osExitReason)"
            thresh > 0 && avail in 0..(thresh * 2) ->
                "LIKELY LMK - " + availMb + "MB free vs " + threshMb + "MB threshold ($osExitReason)"
            lived in 0..10 ->
                "EARLY DEATH - died " + lived + "s after start ($osExitReason)"
            else ->
                "TERMINATED: $osExitReason"
        }

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("===== ABNORMAL PROCESS DEATH =====")
        // SPLENDOR_P10_DW_FRESH_LINE: same instant as detectedMs.
        sb.appendLine("Detected at   : " + ts.format(Date(detectedMs)))
        sb.appendLine("Dead process  : " + deadProc + "  (pid " + deadPid + ")")
        sb.appendLine("Session began : " + (if (began > 0) ts.format(Date(began)) else "?"))
        sb.appendLine("Last heartbeat: " + (if (lastBeat > 0) ts.format(Date(lastBeat)) else "?"))
        sb.appendLine("Survived      : " + lived + "s")
        sb.appendLine("Undetected for: " + gap + "s before this restart")
        sb.appendLine("Memory then   : avail=" + availMb + "MB threshold=" + threshMb + "MB lowMemory=" + lowMem)
        sb.appendLine("Java crash    : " + (if (javaCrash) "YES" else "NO"))
        sb.appendLine("VERDICT       : " + verdict)
        sb.appendLine("Report source : " + CrashForensics.currentLocation())
        sb.appendLine("Marker state  : " + at(0))
        if (nativeCrashDetails.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("--- NATIVE CRASH DETAILED FORENSICS ---")
            sb.appendLine(nativeCrashDetails)
        }
        sb.appendLine("==================================")

        val text = sb.toString()

        try { reportFile()?.appendText(text) } catch (t: Throwable) { android.util.Log.e("DeathWatch", "death report write failed, report lost", t) }
        try { CrashForensics.reportDetectedDeath(deadProc, deadPid, verdict) } catch (_: Throwable) { }

        if (javaCrash) {
            try { javaCrashMarkerFile()?.delete() } catch (_: Throwable) { }
        }

        log("ABNORMAL DEATH proc=" + deadProc + " lived=" + lived + "s avail=" +
            availMb + "MB lowMemory=" + lowMem + " verdict=" + verdict)
    }

    private fun javaCrashMarkerFile(): File? {
        return try {
            val processName = resolveProcessName(null)
            val safeProcess = safeName(processName)
            File(
                SplendorStorageRoot.subdirectory("deathwatch"),
                "$safeProcess.java-crash.marker"
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun javaCrashMarkerPresent(since: Long): Boolean {
        return try {
            val f = javaCrashMarkerFile()

            if (f == null || !f.exists()) {
                false
            } else {
                val timestamp = f.readText()
                    .substringAfter("timestamp=", "")
                    .substringBefore("|")
                    .toLongOrNull()

                timestamp != null && timestamp >= since
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun reportFile(): File? {
        return try {
            SplendorStorageRoot.file(REPORT_NAME)
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- helpers ----------------

    private fun resolveProcessName(c: Context?): String = try {
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            if (c == null) {
                "pid$pid"
            } else {
                val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.runningAppProcesses
                    ?.firstOrNull { it.pid == pid }
                    ?.processName
                    ?: "pid$pid"
            }
        }
    } catch (_: Throwable) {
        "pid" + android.os.Process.myPid()
    }

    private fun safeName(s: String): String =
        s.replace(':', '_').replace('.', '_').replace('/', '_')

    private fun log(m: String) {
        try { com.assistant.diagnostic.RuntimeLogger.log(m, "DEATHWATCH") } catch (_: Throwable) { }
    }

    private fun getHistoricalExitReasonDetails(
        deadPid: Int, deadProc: String,
        beganMs: Long, lastBeatMs: Long, availMb: String, threshMb: String, lowMem: String,
        // SPLENDOR_P10_DW_FRESH_SIG
        detectedMs: Long
    ): Triple<String, String, ApplicationExitInfo?> {
        // SPLENDOR_P9_DW_TRIPLE: the third element is the structured OS
        // exit record (reason/status/importance) so the verdict code can
        // branch on ground truth instead of parsing a formatted string.
        // Null when no OS record exists for this death.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Triple("SDK_LESS_THAN_30", "", null)
        val ctx = appContext ?: return Triple("NO_CONTEXT", "", null)
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exitInfos = am.getHistoricalProcessExitReasons(ctx.packageName, deadPid, 5)
            // SPLENDOR_P9_DW_FALLBACK: no bare firstOrNull() fallback --
            // attaching the most recent exit record when neither pid nor
            // process matches misattributes another session's death.
            val info = exitInfos.firstOrNull { deadPid != 0 && it.pid == deadPid }
                ?: exitInfos.firstOrNull { deadProc.isNotBlank() && it.processName == deadProc }

            if (info != null) {
                var traceNote = ""
                var nativeDetails = ""
                // SPLENDOR_P10_DW_FRESH_FLAG
                var freshTombstoneWritten = false
                if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    try {
                        info.traceInputStream?.use { inputStream ->
                            val traceBytes = inputStream.readBytes()
                            val parsedForensics = parseTombstoneBytes(
                                traceBytes = traceBytes,
                                pkgName = ctx.packageName,
                                procName = if (deadProc != "?") deadProc else info.processName,
                                pid = if (deadPid != 0) deadPid else info.pid,
                                beganMs = beganMs,
                                lastBeatMs = lastBeatMs,
                                // SPLENDOR_P10_DW_FRESH_PARSE
                                detectedMs = detectedMs,
                                availMb = availMb,
                                threshMb = threshMb,
                                lowMem = lowMem,
                                status = info.status,
                                importance = info.importance
                            )

                            nativeDetails = parsedForensics.embeddedSummary

                            // Crash copies: canonical /sdcard/Splendor-Assist only (SplendorStorageRoot)
                            
                            // 3. Save to external forensic storage
                            var extPath = ""
                            try {
                                if (SplendorStorageRoot.isReady()) {
                                    val rawFileExt = SplendorStorageRoot.file("Splendor_Native_Crash.pb")
                                    rawFileExt.writeBytes(traceBytes)
                                    
                                    val txtFileExt = SplendorStorageRoot.file("Splendor_Native_Crash_Readable.txt")
                                    txtFileExt.writeText(parsedForensics.fullReport)
                                    extPath = rawFileExt.absolutePath
                                    // SPLENDOR_P10_DW_FRESH_WRITTEN
                                    freshTombstoneWritten = true
                                }
                            } catch (t: Throwable) { android.util.Log.e("DeathWatch", "native crash forensic copy failed", t) }
                            
                            // SPLENDOR_P10_DW_FRESH_NOTE
                            traceNote = " [Tombstone saved to ${if(extPath.isNotEmpty()) extPath else SplendorStorageRoot.ROOT_PATH}; " + parsedForensics.freshnessLine + "]"
                        }
                    } catch (t: Throwable) {
                        com.assistant.diagnostic.RuntimeLogger.log(
                            "DeathWatch: native crash persistence failed: " + t.message,
                            "DEATHWATCH")
                    }
                }

                val reasonStr = when (info.reason) {
                    ApplicationExitInfo.REASON_ANR -> "REASON_ANR (Application Not Responding)"
                    ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH (Java/Kotlin uncaught exception)"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE (Native C/C++ SIGSEGV/SIGABRT)"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE (Excessive CPU/RAM/Battery)"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF (Clean stopSelf or System.exit)"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE (Process init failed)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY (OS Low Memory Killer / LMK)"
                    ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER (Vendor/HyperOS background eviction or kill)"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE (Permission revoked)"
                    ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED (Killed by OS signal ${info.status})"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED (User force-stop or task swipe)"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED (User stopped application)"
                    else -> "REASON_CODE_${info.reason}"
                }
                // SPLENDOR_P10_DW_FRESH_STALE: a death that captured no new
                // tombstone must not leave the old files standing silent.
                // The Readable is rewritten in place with a STALE banner
                // (never deleted -- the old forensics are preserved below
                // the banner) and the freshness line is mirrored here so
                // the Crash_Reports.txt death entry carries it too.
                if (!freshTombstoneWritten) {
                    val staleNote = markStaleReadable(detectedMs, reasonStr)
                    if (staleNote.isNotEmpty()) traceNote = staleNote
                }
                val osReport = "OS_REPORTED: $reasonStr [status=${info.status} importance=${info.importance}]$traceNote"
                Triple(osReport, nativeDetails, info) /* SPLENDOR_P9_DW_TRIPLE_OK */
            } else {
                // SPLENDOR_P10_DW_FRESH_NORECORD
                Triple("NO_OS_RECORD_FOUND" + markStaleReadable(detectedMs, "NO_OS_RECORD_FOUND"), "", null) /* SPLENDOR_P9_DW_TRIPLE_NO */
            }
        } catch (t: Throwable) {
            Triple("QUERY_FAILED: ${t.javaClass.simpleName}: ${t.message}", "", null) /* SPLENDOR_P9_DW_TRIPLE_QF */
        }
    }

    private data class ParsedTombstone(
        val fullReport: String,
        val embeddedSummary: String,
        val freshnessLine: String
    )

    // SPLENDOR_P10_DW_FRESH_HELPER: rewrites the Readable in place with a STALE
    // banner when a death captured no new tombstone. The previous
    // tombstone time is recovered by wire-parsing the old .pb (or
    // "unknown date" when it cannot be read). The old report body is
    // KEPT below the banner -- deletion would destroy forensics. Returns
    // the trace-note mirror string, or "" when there is nothing to mark.
    private fun markStaleReadable(detectedMs: Long, reasonLabel: String): String {
        return try {
            if (!SplendorStorageRoot.isReady()) return ""
            val txtFile = SplendorStorageRoot.file("Splendor_Native_Crash_Readable.txt")
            if (!txtFile.exists()) return ""
            val pbFile = SplendorStorageRoot.file("Splendor_Native_Crash.pb")
            val prevTs = try {
                if (pbFile.exists()) parseTombstoneProto(pbFile.readBytes())?.ts?.takeIf { it.isNotEmpty() } ?: "unknown date" else "unknown date"
            } catch (_: Throwable) { "unknown date" }
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val banner = "STALE -- from " + prevTs + ". No tombstone was captured for the death detected at " + df.format(Date(detectedMs)) + " (reason " + reasonLabel + "). The backtrace below belongs to an earlier crash.\n"
            txtFile.writeText(banner + txtFile.readText())
            " [Tombstone: STALE -- no new tombstone captured for this death; previous report marked stale in Splendor_Native_Crash_Readable.txt]"
        } catch (_: Throwable) { "" }
    }

    // SPLENDOR_P10_DW_WIRE: hand-rolled length-delimited protobuf wire
    // reader (pure Kotlin, zero new deps -- no protobuf runtime, no
    // .proto schema, no generated classes in the app). debuggerd
    // tombstones are real protobuf; the old strings-dump flattened all
    // 98 threads' frames, registers and mapping names into one
    // unattributed list, so the "backtrace" mixed at least four threads
    // and every field it filled was wrong or "?". Field numbers were
    // decoded from the real tombstone in
    // Splendor-Assist/Splendor_Native_Crash.pb and cross-checked against
    // AOSP tombstone_proto.cpp fill_in_backtrace_frame order (rel_pc,
    // pc, sp, function_name, function_offset, file_name,
    // file_map_offset, build_id). Returns null on any structural error;
    // the caller then falls back to the strings parse, labelled as such.
    private class P10WireReader(val bytes: ByteArray) {
        var pos = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalStateException("varint too long")
            }
            throw IllegalStateException("truncated varint")
        }
        fun readBytes(): ByteArray {
            val n = readVarint().toInt()
            if (n < 0 || pos + n > bytes.size) throw IllegalStateException("length-delimited field overruns buffer")
            val value = bytes.copyOfRange(pos, pos + n)
            pos += n
            return value
        }
        fun readString(): String = readBytes().toString(Charsets.UTF_8)
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> pos += readVarint().toInt()
                5 -> pos += 4
                else -> throw IllegalStateException("bad wire type " + wireType)
            }
            if (pos > bytes.size) throw IllegalStateException("skip overruns buffer")
        }
    }

    private data class P10TombFrame(
        val relPc: Long, val pc: Long, val fn: String, val fnOff: Long,
        val file: String, val buildId: String
    )

    private data class P10Tombstone(
        val ts: String, val pid: Long, val tid: Long, val fingerprint: String,
        val sigNo: Long, val sigName: String, val sigCode: String, val detail: String,
        val crashThreadName: String, val frames: List<P10TombFrame>,
        val otherThreadCount: Int
    )

    private fun parseTombstoneProto(traceBytes: ByteArray): P10Tombstone? {
        return try {
            val reader = P10WireReader(traceBytes)
            var fingerprint = ""
            var ts = ""
            var pid = 0L
            var tid = 0L
            var sigNo = 0L
            var sigName = ""
            var sigCode = ""
            var detail = ""
            val threadBlobs = mutableListOf<ByteArray>()
            var threadCount = 0
            while (reader.pos < traceBytes.size) {
                val tag = reader.readVarint()
                val field = (tag shr 3).toInt()
                val wireType = (tag and 7L).toInt()
                when (field) {
                    2 -> if (wireType == 2) fingerprint = reader.readString() else reader.skip(wireType)
                    4 -> if (wireType == 2) ts = reader.readString() else reader.skip(wireType)
                    5 -> if (wireType == 0) pid = reader.readVarint() else reader.skip(wireType)
                    6 -> if (wireType == 0) tid = reader.readVarint() else reader.skip(wireType)
                    10 -> if (wireType == 2) {
                        val cause = P10WireReader(reader.readBytes())
                        while (cause.pos < cause.bytes.size) {
                            val cTag = cause.readVarint()
                            val cField = (cTag shr 3).toInt()
                            val cWt = (cTag and 7L).toInt()
                            when (cField) {
                                1 -> if (cWt == 0) sigNo = cause.readVarint() else cause.skip(cWt)
                                2 -> if (cWt == 2) sigName = cause.readString() else cause.skip(cWt)
                                4 -> if (cWt == 2) sigCode = cause.readString() else cause.skip(cWt)
                                else -> cause.skip(cWt)
                            }
                        }
                    } else reader.skip(wireType)
                    15 -> if (wireType == 2) {
                        val detailReader = P10WireReader(reader.readBytes())
                        while (detailReader.pos < detailReader.bytes.size) {
                            val dTag = detailReader.readVarint()
                            val dField = (dTag shr 3).toInt()
                            val dWt = (dTag and 7L).toInt()
                            if (dField == 1 && dWt == 2) detail = detailReader.readString() else detailReader.skip(dWt)
                        }
                    } else reader.skip(wireType)
                    16 -> if (wireType == 2) { threadBlobs.add(reader.readBytes()); threadCount++ } else reader.skip(wireType)
                    else -> reader.skip(wireType)
                }
            }
            var crashName = ""
            var crashFrames = listOf<P10TombFrame>()
            var foundCrash = false
            for (blob in threadBlobs) {
                val threadReader = P10WireReader(blob)
                var threadTid = 0L
                var infoBytes: ByteArray? = null
                while (threadReader.pos < blob.size) {
                    val tTag = threadReader.readVarint()
                    val tField = (tTag shr 3).toInt()
                    val tWt = (tTag and 7L).toInt()
                    when (tField) {
                        1 -> if (tWt == 0) threadTid = threadReader.readVarint() else threadReader.skip(tWt)
                        2 -> if (tWt == 2) infoBytes = threadReader.readBytes() else threadReader.skip(tWt)
                        else -> threadReader.skip(tWt)
                    }
                }
                val info = infoBytes ?: continue
                val infoReader = P10WireReader(info)
                var infoTid = 0L
                var name = ""
                val frames = mutableListOf<P10TombFrame>()
                while (infoReader.pos < info.size) {
                    val iTag = infoReader.readVarint()
                    val iField = (iTag shr 3).toInt()
                    val iWt = (iTag and 7L).toInt()
                    when (iField) {
                        1 -> if (iWt == 0) infoTid = infoReader.readVarint() else infoReader.skip(iWt)
                        2 -> if (iWt == 2) name = infoReader.readString() else infoReader.skip(iWt)
                        4 -> if (iWt == 2) {
                            val frameReader = P10WireReader(infoReader.readBytes())
                            var relPc = 0L
                            var pc = 0L
                            var fn = ""
                            var fnOff = 0L
                            var file = ""
                            var buildId = ""
                            while (frameReader.pos < frameReader.bytes.size) {
                                val fTag = frameReader.readVarint()
                                val fField = (fTag shr 3).toInt()
                                val fWt = (fTag and 7L).toInt()
                                when (fField) {
                                    1 -> if (fWt == 0) relPc = frameReader.readVarint() else frameReader.skip(fWt)
                                    2 -> if (fWt == 0) pc = frameReader.readVarint() else frameReader.skip(fWt)
                                    4 -> if (fWt == 2) fn = frameReader.readString() else frameReader.skip(fWt)
                                    5 -> if (fWt == 0) fnOff = frameReader.readVarint() else frameReader.skip(fWt)
                                    6 -> if (fWt == 2) file = frameReader.readString() else frameReader.skip(fWt)
                                    8 -> if (fWt == 2) buildId = frameReader.readString() else frameReader.skip(fWt)
                                    else -> frameReader.skip(fWt)
                                }
                            }
                            frames.add(P10TombFrame(relPc, pc, fn, fnOff, file, buildId))
                        } else infoReader.skip(iWt)
                        else -> infoReader.skip(iWt)
                    }
                }
                val effectiveTid = if (threadTid != 0L) threadTid else infoTid
                if (tid != 0L && effectiveTid == tid) {
                    crashName = name
                    crashFrames = frames
                    foundCrash = true
                    break
                }
            }
            if (!foundCrash) return null
            P10Tombstone(ts, pid, tid, fingerprint, sigNo, sigName, sigCode, detail, crashName, crashFrames, (threadCount - 1).coerceAtLeast(0))
        } catch (_: Throwable) { null }
    }

    private fun parseTombstoneTimeMs(ts: String): Long {
        // "2026-11-04 04:36:18.939317518+0100": keep millis precision only.
        return try {
            val m = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\.(\\d{3})\\d*([+-]\\d{4})$").matchEntire(ts) ?: return -1L
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).parse(m.groupValues[1] + "." + m.groupValues[2] + m.groupValues[3])?.time ?: -1L
        } catch (_: Throwable) { -1L }
    }

    private fun parseTombstoneBytes(
        traceBytes: ByteArray,
        pkgName: String,
        procName: String,
        pid: Int,
        beganMs: Long,
        lastBeatMs: Long,
        detectedMs: Long,
        availMb: String,
        threshMb: String,
        lowMem: String,
        status: Int,
        importance: Int
    ): ParsedTombstone {
        val extractedStrings = mutableListOf<String>()
        val curr = StringBuilder()
        for (b in traceBytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                curr.append(c.toChar())
            } else {
                if (curr.length >= 3) extractedStrings.add(curr.toString())
                curr.clear()
            }
        }
        if (curr.length >= 3) extractedStrings.add(curr.toString())

        var timestampStr = "?"
        // SPLENDOR_P9_DW_TOMBSTONE: these were fabricated defaults
        // ("SIGSEGV"/"SEGV_MAPERR"/"0x0"/"HeapTaskDaemon") printed as if
        // parsed whenever the tombstone contained no matching string.
        // Unknown until the parse proves otherwise.
        var signalStr = "?"
        var signalCodeStr = "?"
        var faultAddrStr = "?"
        var crashingThreadStr = "?"
        var crashingTidStr = "?"
        val backtraceFrames = mutableListOf<String>()
        val appNativeFrames = mutableListOf<String>()
        var mainLibPath = "?"
        var mainBuildId = "?"
        // SPLENDOR_P10_DW_WIRE_VARS: set by the wire parse (or left at
        // these defaults by the strings fallback) -- see the classifier
        // replacement below.
        var topSymbol = "Unknown location"
        var wireParsed = false
        var otherThreadCount = 0
        var tombstoneTimeMs = -1L
        var fingerprintStr = "?"
        var jniInvolved: Boolean

        // SPLENDOR_P10_DW_WIRE_CLASS: try the length-delimited wire parse
        // first. The old strings-dump classifier below is kept ONLY as a
        // labelled fallback when the wire parse returns null: the dump
        // flattens all 98 threads into one unattributed list, so its
        // thread name, top frame, library path and build ID were wrong
        // for this tombstone (proven by simulation on the real .pb).
        // Deleted here: the HeapTaskDaemon/RenderThread/main thread
        // heuristic (it could never return the crashing thread), the
        // substringAfter(" ") library-path hack (it returned the libart
        // build-ID hex), and the 32-hex build-ID branch (unreachable --
        // every build ID was glued into a frame string first).
        val wire = try { parseTombstoneProto(traceBytes) } catch (_: Throwable) { null }
        if (wire != null) {
            wireParsed = true
            timestampStr = wire.ts.ifEmpty { "?" }
            signalStr = wire.sigName.ifEmpty { "?" }
            signalCodeStr = wire.sigCode.ifEmpty { "?" }
            faultAddrStr = "not recorded in tombstone"
            crashingThreadStr = wire.crashThreadName.ifEmpty { "?" }
            crashingTidStr = wire.tid.toString()
            fingerprintStr = wire.fingerprint.ifEmpty { "?" }
            otherThreadCount = wire.otherThreadCount
            tombstoneTimeMs = parseTombstoneTimeMs(wire.ts)
            for (fr in wire.frames) {
                val buildPart = if (fr.buildId.isNotEmpty()) " [build " + fr.buildId + "]" else ""
                val line = "pc 0x" + fr.relPc.toString(16) + " " + fr.file + " (" + fr.fn + "+" + fr.fnOff + ")" + buildPart
                if (backtraceFrames.size < 35) backtraceFrames.add(line)
                if (fr.fn.contains("com.assistant") || fr.file.contains("libsplendor") || fr.file.contains("libnative")) {
                    appNativeFrames.add(line)
                }
            }
            val first = wire.frames.firstOrNull()
            if (first != null) {
                topSymbol = first.fn
                mainLibPath = first.file
                mainBuildId = first.buildId.ifEmpty { "?" }
            }
        } else {
            for (s in extractedStrings) {
                if (s.matches(Regex("^202[0-9]-[0-9]{2}-[0-9]{2}.*"))) {
                    timestampStr = s
                } else if (s.startsWith("SIG")) {
                    signalStr = s
                } else if (s.startsWith("SEGV_") || s.startsWith("BUS_") || s.startsWith("FPE_") || s.startsWith("ILL_")) {
                    signalCodeStr = s
                } else if (s.startsWith("0x") && s.length in 3..18) {
                    faultAddrStr = s
                } else if (s.contains("libart.so") || s.contains("libsplendor") || s.contains("libnative") || s.contains("::") || s.contains("art::")) {
                    if (backtraceFrames.size < 35 && s.length > 5) {
                        backtraceFrames.add(s)
                        if (s.contains("libsplendor") || s.contains("libnative") || s.contains("com.assistant")) {
                            appNativeFrames.add(s)
                        }
                        if (mainLibPath == "?" && s.contains(".so")) {
                            val pathMatch = Regex("/\\S*?\\.so").find(s)
                            if (pathMatch != null) mainLibPath = pathMatch.value
                        }
                    }
                }
            }
            topSymbol = backtraceFrames.firstOrNull() ?: "Unknown location"
        }
        jniInvolved = backtraceFrames.any { it.contains("JNIEnv") || it.contains("jni", ignoreCase = true) }

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sessionStartStr = if (beganMs > 0) df.format(Date(beganMs)) else "?"
        val uptimeSec = if (beganMs > 0 && lastBeatMs >= beganMs) (lastBeatMs - beganMs) / 1000L else 0L
        // SPLENDOR_P10_DW_TS_FALLBACK: the wire timestamp is the crash
        // time; when it is absent, lastBeatMs is only a lower bound
        // (last heartbeat, up to 15s + detection gap earlier) -- never
        // print it as a bare crash time.
        if (timestampStr == "?") {
            timestampStr = if (lastBeatMs > 0) "after " + df.format(Date(lastBeatMs)) + " (last heartbeat; exact crash time not in tombstone)" else "?"
        }

        val topFrame = topSymbol

        // SPLENDOR_P10_DW_TS_FRESHNESS: detection time + tombstone time +
        // age, stamped into the header so a reader can tell which death
        // this report belongs to. STALE when the tombstone is older than
        // the heartbeat window + margin (120s) or predates the session.
        val detectedStr = df.format(Date(detectedMs))
        val tombstoneStr = if (tombstoneTimeMs > 0) df.format(Date(tombstoneTimeMs)) else "not present in tombstone"
        val tombstoneAgeSec = if (tombstoneTimeMs > 0) (detectedMs - tombstoneTimeMs) / 1000L else -1L
        val tombstoneFresh = tombstoneTimeMs > 0 && tombstoneAgeSec >= 0L && tombstoneAgeSec <= 120L && (beganMs <= 0L || tombstoneTimeMs >= beganMs)
        val freshnessLine = if (tombstoneTimeMs <= 0) {
            "tombstone freshness: UNKNOWN -- no tombstone timestamp parsed; do NOT treat this report as the current crash without the .pb file"
        } else if (tombstoneFresh) {
            "tombstone freshness: CURRENT -- captured for this death (tombstone " + tombstoneStr + ", detected " + detectedStr + ", age " + tombstoneAgeSec + "s)"
        } else {
            "tombstone freshness: STALE -- from " + tombstoneStr + " (" + tombstoneAgeSec + "s old); do NOT treat as the current crash"
        }

        val fullSb = StringBuilder()
        fullSb.appendLine("============================================================")
        fullSb.appendLine("SPLENDOR-ASSIST NATIVE CRASH FORENSIC REPORT")
        fullSb.appendLine("============================================================")
        // SPLENDOR_P10_DW_FRESH_HEADER
        fullSb.appendLine("  report generated : " + detectedStr + " (death detected at this restart)")
        fullSb.appendLine("  tombstone time   : " + tombstoneStr)
        fullSb.appendLine("  " + freshnessLine)
        if (!wireParsed) fullSb.appendLine("  [FALLBACK strings-parse -- thread attribution unreliable]")
        fullSb.appendLine("PROCESS")
        fullSb.appendLine("  package        : $pkgName")
        fullSb.appendLine("  process        : $procName")
        fullSb.appendLine("  PID            : $pid")
        fullSb.appendLine("  crash timestamp: $timestampStr")
        fullSb.appendLine("  process start  : $sessionStartStr")
        // SPLENDOR_P10_DW_FRESH_UPTIME: uptime is measured to the last
        // heartbeat, not to the crash (off by <= 15s + detection gap).
        fullSb.appendLine("  process uptime : ${uptimeSec}s (to last heartbeat)")
        fullSb.appendLine()
        fullSb.appendLine("SIGNAL")
        fullSb.appendLine("  signal         : $signalStr")
        fullSb.appendLine("  signal code    : $signalCodeStr")
        fullSb.appendLine("  fault address  : $faultAddrStr")
        fullSb.appendLine("  errno/status   : status=$status")
        fullSb.appendLine()
        fullSb.appendLine("CRASHING THREAD")
        fullSb.appendLine("  TID            : $crashingTidStr")
        fullSb.appendLine("  thread name    : $crashingThreadStr")
        // SPLENDOR_P10_DW_TOMB_STATE: thread state is a fact about
        // the parsed signal + thread, not an assertion from a signal
        // string. No thread-state field exists in tombstone Thread
        // records, so the old FATAL_SIGNAL_RECEIVED line is deleted.
        if (wireParsed && signalStr != "?") fullSb.appendLine("  thread state   : signal " + signalStr + " received by tid " + crashingTidStr + " (" + crashingThreadStr + ")")
        else fullSb.appendLine("  thread state   : Unknown (no signal parsed from tombstone)")
        fullSb.appendLine()
        fullSb.appendLine("CRASH LOCATION")
        fullSb.appendLine("  top symbol     : $topFrame")
        fullSb.appendLine("  library path   : $mainLibPath")
        fullSb.appendLine("  build ID       : $mainBuildId")
        fullSb.appendLine()
        fullSb.appendLine("NATIVE BACKTRACE")
        // SPLENDOR_P10_DW_WIRE_HDR
        if (wireParsed) fullSb.appendLine("  crashing thread: " + crashingThreadStr + " (tid " + crashingTidStr + ") -- " + backtraceFrames.size + " frames; " + otherThreadCount + " other threads present, not shown")
        else fullSb.appendLine("  [FALLBACK strings-parse -- thread attribution unreliable]")
        if (backtraceFrames.isEmpty()) {
            fullSb.appendLine("  [No frames parsed from tombstone trace stream]")
        } else {
            backtraceFrames.forEachIndexed { i, frame ->
                fullSb.appendLine("  #%02d: %s".format(i, frame))
            }
        }
        fullSb.appendLine()
        fullSb.appendLine("APPLICATION NATIVE FRAMES")
        if (appNativeFrames.isEmpty()) {
            // SPLENDOR_P10_DW_WIRE_APP
            fullSb.appendLine("  [None observed in top backtrace; execution was inside system libraries (no app frames in the parsed backtrace)]")
        } else {
            appNativeFrames.forEach { frame ->
                fullSb.appendLine("  -> $frame")
            }
        }
        fullSb.appendLine()
        fullSb.appendLine("JNI / NATIVE FORENSIC CONTEXT")
        // SPLENDOR_P10_DW_JVM: the old line hardcoded Android 16 and
        // a Compacting GC claim the tombstone never evidences. Derive the
        // release at runtime; the fingerprint comes from the wire parse.
        fullSb.appendLine("  target runtime : Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "; ART; fingerprint " + fingerprintStr + "; GC type not recorded in tombstone)")
        // SPLENDOR_P9_DW_TOMB_JNI: the old line asserted active JNI
        // operations / GC scanning regardless of evidence.
        fullSb.appendLine("  JNI status     : Unknown (tombstone parse does not evidence JNI activity)")
        fullSb.appendLine()
        fullSb.appendLine("RECENT NATIVE EVENTS")
        // SPLENDOR_P9_DW_TOMB_EVENT: the old line claimed a correlation
        // with recent runtime activity that was never performed.
        fullSb.appendLine("  [No correlated native events recorded -- none inferred]")
        fullSb.appendLine()
        fullSb.appendLine("MEMORY STATE")
        fullSb.appendLine("  available RAM  : ${availMb}MB")
        fullSb.appendLine("  RAM threshold  : ${threshMb}MB")
        fullSb.appendLine("  low-memory     : $lowMem")
        fullSb.appendLine()
        fullSb.appendLine("OS EXIT RECORD")
        fullSb.appendLine("  reason         : REASON_CRASH_NATIVE")
        fullSb.appendLine("  status         : $status")
        fullSb.appendLine("  importance     : $importance")
        fullSb.appendLine()
        fullSb.appendLine("DIAGNOSTIC CONCLUSION")
        // SPLENDOR_P10_DW_TOMB_CONCL: crash class / root cause /
        // recommendation follow the wire parse only. The old
        // unconditional JNI steer survives only when a parsed frame is
        // actually JNI-related; a GC cause is never inferred.
        if (wireParsed && signalStr != "?") fullSb.appendLine("  crash class    : Native crash ($signalStr)")
        else fullSb.appendLine("  crash class    : Unknown (no signal parsed from tombstone)")
        fullSb.appendLine("  crashing entity: $crashingThreadStr ($topFrame)")
        if (wireParsed && signalStr != "?") {
            if (jniInvolved) fullSb.appendLine("  root cause type: Undetermined from tombstone -- parsed frames include JNI entry points; audit JNI array writes, boundary lengths, and direct pointer lifetimes")
            else fullSb.appendLine("  root cause type: Undetermined from tombstone -- inspect the crashing thread's frames above")
        } else if (signalStr != "?") fullSb.appendLine("  root cause type: Undetermined from tombstone strings -- inspect the parsed frames above; do not infer a GC cause")
        else fullSb.appendLine("  root cause type: Undetermined (no signal/thread parsed from tombstone; do not infer a GC cause)")
        if (jniInvolved) fullSb.appendLine("  recommendation : Audit JNI array writes, boundary lengths, and direct pointer lifetimes")
        else fullSb.appendLine("  recommendation : Undetermined -- inspect the crashing thread's frames above")
        fullSb.appendLine("============================================================")

        val embSb = StringBuilder()
        embSb.appendLine("Signal         : $signalStr ($signalCodeStr) [status=$status]")
        embSb.appendLine("Fault Address  : $faultAddrStr")
        embSb.appendLine("Crashing Thread: $crashingThreadStr")
        embSb.appendLine("Crash Location : $topFrame")
        embSb.appendLine()
        embSb.appendLine("Native Backtrace:")
        if (backtraceFrames.isEmpty()) {
            embSb.appendLine("  [No frames available]")
        } else {
            backtraceFrames.take(15).forEachIndexed { i, frame ->
                embSb.appendLine("  #%02d: %s".format(i, frame))
            }
        }
        embSb.appendLine()
        embSb.appendLine("Application Native Frames:")
        if (appNativeFrames.isEmpty()) {
            // SPLENDOR_P10_DW_WIRE_EMBAPP
            embSb.appendLine("  [None observed in top frames; crash occurred inside system libraries (no app frames in the parsed backtrace)]")
        } else {
            appNativeFrames.forEach { frame ->
                embSb.appendLine("  -> $frame")
            }
        }
        embSb.appendLine()
        embSb.appendLine("Diagnostic Verdict:")
        // SPLENDOR_P10_DW_VERDICT: the P9 miss -- the embedded summary
        // still asserted a GC/JNI root cause with the wrong thread and
        // wrong top frame. Rewritten from wire-parsed values only; the
        // GC/JNI sentence is deleted.
        if (wireParsed && signalStr != "?") {
            embSb.appendLine("  Native " + signalStr + " (" + signalCodeStr + ") in " + crashingThreadStr + " (tid " + crashingTidStr + ") at " + topFrame + " (" + mainLibPath + ").")
            embSb.appendLine("  Root cause undetermined from tombstone; " + backtraceFrames.size + " frames parsed from the crashing thread.")
        } else {
            embSb.appendLine("  Native " + signalStr + " (" + signalCodeStr + ") in " + crashingThreadStr + " at " + topFrame + " (strings-parse fallback -- thread/frame attribution unreliable).")
            embSb.appendLine("  Root cause undetermined from tombstone.")
        }

        // SPLENDOR_P10_DW_FRESH_RETURN
        return ParsedTombstone(fullSb.toString(), embSb.toString(), freshnessLine)
    }
}
