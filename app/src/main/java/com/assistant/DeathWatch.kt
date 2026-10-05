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
            try { reportDeath(m.readText()) } catch (_: Throwable) { }
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
        try { t.start() } catch (_: Throwable) { }

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

        val osExitReason = getHistoricalExitReason(deadPid.toIntOrNull() ?: 0, deadProc)
        val verdict = when {
            javaCrash ->
                "JAVA EXCEPTION - a crash report exists for this session ($osExitReason)"
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
        sb.appendLine("Detected at   : " + ts.format(Date()))
        sb.appendLine("Dead process  : " + deadProc + "  (pid " + deadPid + ")")
        sb.appendLine("Session began : " + (if (began > 0) ts.format(Date(began)) else "?"))
        sb.appendLine("Last heartbeat: " + (if (lastBeat > 0) ts.format(Date(lastBeat)) else "?"))
        sb.appendLine("Survived      : " + lived + "s")
        sb.appendLine("Undetected for: " + gap + "s before this restart")
        sb.appendLine("Memory then   : avail=" + availMb + "MB threshold=" + threshMb + "MB lowMemory=" + lowMem)
        sb.appendLine("Java crash    : " + (if (javaCrash) "YES" else "NO"))
        sb.appendLine("VERDICT       : " + verdict)
        sb.appendLine("Marker state  : " + at(0))
        sb.appendLine("==================================")

        val text = sb.toString()

        try { reportFile()?.appendText(text) } catch (_: Throwable) { }

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

    private fun getHistoricalExitReason(deadPid: Int, deadProc: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "SDK_LESS_THAN_30"
        val ctx = appContext ?: return "NO_CONTEXT"
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exitInfos = am.getHistoricalProcessExitReasons(ctx.packageName, deadPid, 5)
            val info = exitInfos.firstOrNull { deadPid != 0 && it.pid == deadPid }
                ?: exitInfos.firstOrNull { deadProc.isNotBlank() && it.processName == deadProc }
                ?: exitInfos.firstOrNull()

            if (info != null) {
                var traceNote = ""
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    try {
                        info.traceInputStream?.use { inputStream ->
                            val traceBytes = inputStream.readBytes()
                            
                            // Helper to extract printable strings from binary protobuf tombstone
                            fun extractStrings(bytes: ByteArray): String {
                                val sb = StringBuilder()
                                val current = StringBuilder()
                                for (b in bytes) {
                                    val c = b.toInt() and 0xFF
                                    if (c in 32..126) {
                                        current.append(c.toChar())
                                    } else {
                                        if (current.length >= 4) {
                                            sb.appendLine(current.toString())
                                        }
                                        current.clear()
                                    }
                                }
                                if (current.length >= 4) sb.appendLine(current.toString())
                                return sb.toString()
                            }

                            val readableStr = extractStrings(traceBytes)
                            val header = "=== NATIVE TOMBSTONE (Raw Binary Saved as .pb) ===\n" +
                                         "Extracted Printable Strings (Libraries, Symbols, Paths):\n\n"
                            val readableReport = header + readableStr

                            // 1. Save RAW BYTES to internal storage (Crucial for protoc --decode_raw)
                            val rawFileInt = java.io.File(ctx.filesDir, "Splendor_Native_Crash.pb")
                            rawFileInt.writeBytes(traceBytes)
                            
                            // 2. Save READABLE REPORT to internal storage
                            val txtFileInt = java.io.File(ctx.filesDir, "Splendor_Native_Crash_Readable.txt")
                            txtFileInt.writeText(readableReport)
                            
                            // 3. Save to external forensic storage (SplendorStorageRoot)
                            var extPath = ""
                            try {
                                if (SplendorStorageRoot.isReady()) {
                                    val rawFileExt = SplendorStorageRoot.file("Splendor_Native_Crash.pb")
                                    rawFileExt.writeBytes(traceBytes)
                                    
                                    val txtFileExt = SplendorStorageRoot.file("Splendor_Native_Crash_Readable.txt")
                                    txtFileExt.writeText(readableReport)
                                    extPath = rawFileExt.absolutePath
                                }
                            } catch (_: Throwable) {}
                            
                            traceNote = " [Tombstone saved to ${if(extPath.isNotEmpty()) extPath else rawFileInt.absolutePath}]"
                        }
                    } catch (_: Throwable) {}
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
                "OS_REPORTED: $reasonStr [status=${info.status} importance=${info.importance}]$traceNote"
            } else {
                "NO_OS_RECORD_FOUND"
            }
        } catch (t: Throwable) {
            "QUERY_FAILED: ${t.javaClass.simpleName}: ${t.message}"
        }
    }
}
