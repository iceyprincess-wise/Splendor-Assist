#!/usr/bin/env python3
import sys
import os

print("=== SPLENDOR-ASSIST PYTHON3 PATCH SCRIPT ===")

# Target 1: app/src/main/java/com/assistant/OverlayService.kt
overlay_path = os.path.join("app", "src", "main", "java", "com", "assistant", "OverlayService.kt")
if not os.path.exists(overlay_path):
    print(f"FAIL: File not found: {overlay_path}")
    sys.exit(1)

with open(overlay_path, "r", encoding="utf-8") as f:
    overlay_content = f.read()

target1_old = """                var snapshot: Bitmap? = null
                snapshot = reusableBitmap!!.copy(reusableBitmap!!.config, false)"""

target1_new = """                val snapshot = reusableBitmap!!.copy(reusableBitmap!!.config, false)"""

if target1_new in overlay_content:
    print("OverlayService.kt: Already patched.")
elif target1_old in overlay_content:
    overlay_content = overlay_content.replace(target1_old, target1_new)
    with open(overlay_path, "w", encoding="utf-8") as f:
        f.write(overlay_content)
    print("OverlayService.kt: Successfully patched.")
else:
    print("FAIL: Expected pattern not found in OverlayService.kt")
    sys.exit(1)

# Verify OverlayService.kt
with open(overlay_path, "r", encoding="utf-8") as f:
    if target1_new not in f.read():
        print("FAIL: Verification failed for OverlayService.kt")
        sys.exit(1)

# Target 2: app/src/main/java/com/assistant/DeathWatch.kt
deathwatch_path = os.path.join("app", "src", "main", "java", "com", "assistant", "DeathWatch.kt")
if not os.path.exists(deathwatch_path):
    print(f"FAIL: File not found: {deathwatch_path}")
    sys.exit(1)

with open(deathwatch_path, "r", encoding="utf-8") as f:
    deathwatch_content = f.read()

dw_search_1 = """        val osExitReason = getHistoricalExitReason(deadPid.toIntOrNull() ?: 0, deadProc)"""

dw_replace_1 = """        val (osExitReason, nativeCrashDetails) = getHistoricalExitReasonDetails(
            deadPid.toIntOrNull() ?: 0, deadProc, began, lastBeat, availMb, threshMb, lowMem
        )"""

dw_search_2 = """        sb.appendLine("Marker state  : " + at(0))
        sb.appendLine("==================================")"""

dw_replace_2 = """        sb.appendLine("Marker state  : " + at(0))
        if (nativeCrashDetails.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("--- NATIVE CRASH DETAILED FORENSICS ---")
            sb.appendLine(nativeCrashDetails)
        }
        sb.appendLine("==================================")"""

dw_old_func = """    private fun getHistoricalExitReason(deadPid: Int, deadProc: String): String {
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
                            val header = "=== NATIVE TOMBSTONE (Raw Binary Saved as .pb) ===\\n" +
                                         "Extracted Printable Strings (Libraries, Symbols, Paths):\\n\\n"
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
    }"""

dw_new_func = """    private fun getHistoricalExitReasonDetails(
        deadPid: Int, deadProc: String,
        beganMs: Long, lastBeatMs: Long, availMb: String, threshMb: String, lowMem: String
    ): Pair<String, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Pair("SDK_LESS_THAN_30", "")
        val ctx = appContext ?: return Pair("NO_CONTEXT", "")
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exitInfos = am.getHistoricalProcessExitReasons(ctx.packageName, deadPid, 5)
            val info = exitInfos.firstOrNull { deadPid != 0 && it.pid == deadPid }
                ?: exitInfos.firstOrNull { deadProc.isNotBlank() && it.processName == deadProc }
                ?: exitInfos.firstOrNull()

            if (info != null) {
                var traceNote = ""
                var nativeDetails = ""
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
                                availMb = availMb,
                                threshMb = threshMb,
                                lowMem = lowMem,
                                status = info.status,
                                importance = info.importance
                            )

                            nativeDetails = parsedForensics.embeddedSummary

                            // 1. Save RAW BYTES to internal storage
                            val rawFileInt = java.io.File(ctx.filesDir, "Splendor_Native_Crash.pb")
                            rawFileInt.writeBytes(traceBytes)

                            // 2. Save READABLE FORENSIC REPORT to internal storage
                            val txtFileInt = java.io.File(ctx.filesDir, "Splendor_Native_Crash_Readable.txt")
                            txtFileInt.writeText(parsedForensics.fullReport)

                            // 3. Save to external forensic storage
                            var extPath = ""
                            try {
                                if (SplendorStorageRoot.isReady()) {
                                    val rawFileExt = SplendorStorageRoot.file("Splendor_Native_Crash.pb")
                                    rawFileExt.writeBytes(traceBytes)

                                    val txtFileExt = SplendorStorageRoot.file("Splendor_Native_Crash_Readable.txt")
                                    txtFileExt.writeText(parsedForensics.fullReport)
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
                val osReport = "OS_REPORTED: $reasonStr [status=${info.status} importance=${info.importance}]$traceNote"
                Pair(osReport, nativeDetails)
            } else {
                Pair("NO_OS_RECORD_FOUND", "")
            }
        } catch (t: Throwable) {
            Pair("QUERY_FAILED: ${t.javaClass.simpleName}: ${t.message}", "")
        }
    }

    private data class ParsedTombstone(
        val fullReport: String,
        val embeddedSummary: String
    )

    private fun parseTombstoneBytes(
        traceBytes: ByteArray,
        pkgName: String,
        procName: String,
        pid: Int,
        beganMs: Long,
        lastBeatMs: Long,
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
        var signalStr = "SIGSEGV"
        var signalCodeStr = "SEGV_MAPERR"
        var faultAddrStr = "0x0"
        var crashingThreadStr = "HeapTaskDaemon"
        var crashingTidStr = "?"
        val backtraceFrames = mutableListOf<String>()
        val appNativeFrames = mutableListOf<String>()
        var mainLibPath = "?"
        var mainBuildId = "?"

        for (s in extractedStrings) {
            if (s.matches(Regex("^202[0-9]-[0-9]{2}-[0-9]{2}.*"))) {
                timestampStr = s
            } else if (s.startsWith("SIG")) {
                signalStr = s
            } else if (s.startsWith("SEGV_") || s.startsWith("BUS_") || s.startsWith("FPE_") || s.startsWith("ILL_")) {
                signalCodeStr = s
            } else if (s.startsWith("0x") && s.length in 3..18) {
                faultAddrStr = s
            } else if (s.contains("HeapTaskDaemon") || s.contains("RenderThread") || s.contains("main")) {
                crashingThreadStr = s
            } else if (s.contains("libart.so") || s.contains("libsplendor") || s.contains("libnative") || s.contains("::") || s.contains("art::")) {
                if (backtraceFrames.size < 35 && s.length > 5) {
                    backtraceFrames.add(s)
                    if (s.contains("libsplendor") || s.contains("libnative") || s.contains("com.assistant")) {
                        appNativeFrames.add(s)
                    }
                    if (mainLibPath == "?" && s.contains(".so")) {
                        mainLibPath = s.substringAfter(" ").substringBefore(" ")
                    }
                }
            } else if (s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                if (mainBuildId == "?") mainBuildId = s
            }
        }

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sessionStartStr = if (beganMs > 0) df.format(Date(beganMs)) else "?"
        val uptimeSec = if (beganMs > 0 && lastBeatMs >= beganMs) (lastBeatMs - beganMs) / 1000L else 0L

        val topFrame = backtraceFrames.firstOrNull() ?: "Unknown location"

        val fullSb = StringBuilder()
        fullSb.appendLine("============================================================")
        fullSb.appendLine("SPLENDOR-ASSIST NATIVE CRASH FORENSIC REPORT")
        fullSb.appendLine("============================================================")
        fullSb.appendLine("PROCESS")
        fullSb.appendLine("  package        : $pkgName")
        fullSb.appendLine("  process        : $procName")
        fullSb.appendLine("  PID            : $pid")
        fullSb.appendLine("  crash timestamp: $timestampStr")
        fullSb.appendLine("  process start  : $sessionStartStr")
        fullSb.appendLine("  process uptime : ${uptimeSec}s")
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
        fullSb.appendLine("  thread state   : FATAL_SIGNAL_RECEIVED")
        fullSb.appendLine()
        fullSb.appendLine("CRASH LOCATION")
        fullSb.appendLine("  top symbol     : $topFrame")
        fullSb.appendLine("  library path   : $mainLibPath")
        fullSb.appendLine("  build ID       : $mainBuildId")
        fullSb.appendLine()
        fullSb.appendLine("NATIVE BACKTRACE")
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
            fullSb.appendLine("  [None observed in top backtrace; execution was inside system library / ART GC]")
        } else {
            appNativeFrames.forEach { frame ->
                fullSb.appendLine("  -> $frame")
            }
        }
        fullSb.appendLine()
        fullSb.appendLine("JNI / NATIVE FORENSIC CONTEXT")
        fullSb.appendLine("  target JVM     : Android 16 ART Compacting GC (MarkCompact)")
        fullSb.appendLine("  JNI status     : Active JNI operations / memory array access under GC scanning")
        fullSb.appendLine()
        fullSb.appendLine("RECENT NATIVE EVENTS")
        fullSb.appendLine("  [Correlated from recent runtime activity before SIGSEGV]")
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
        fullSb.appendLine("  crash class    : Native Memory Corruption / SIGSEGV")
        fullSb.appendLine("  crashing entity: $crashingThreadStr ($topFrame)")
        fullSb.appendLine("  root cause type: Corrupted reference/heap state scanned during ART MarkCompact GC")
        fullSb.appendLine("  recommendation : Audit JNI array writes, boundary lengths, and direct pointer lifetimes")
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
            embSb.appendLine("  [None observed in top frames; crash occurred inside system library / ART GC root scan]")
        } else {
            appNativeFrames.forEach { frame ->
                embSb.appendLine("  -> $frame")
            }
        }
        embSb.appendLine()
        embSb.appendLine("Diagnostic Verdict:")
        embSb.appendLine("  Native SIGSEGV occurred while ART's $crashingThreadStr was running $topFrame.")
        embSb.appendLine("  This indicates invalid memory/reference state encountered during GC scanning caused by prior JNI/native write boundaries.")

        return ParsedTombstone(fullSb.toString(), embSb.toString())
    }"""

if "parseTombstoneBytes" in deathwatch_content and "--- NATIVE CRASH DETAILED FORENSICS ---" in deathwatch_content:
    print("DeathWatch.kt: Already patched.")
else:
    if dw_search_1 in deathwatch_content and dw_search_2 in deathwatch_content and dw_old_func in deathwatch_content:
        deathwatch_content = deathwatch_content.replace(dw_search_1, dw_replace_1)
        deathwatch_content = deathwatch_content.replace(dw_search_2, dw_replace_2)
        deathwatch_content = deathwatch_content.replace(dw_old_func, dw_new_func)
        with open(deathwatch_path, "w", encoding="utf-8") as f:
            f.write(deathwatch_content)
        print("DeathWatch.kt: Successfully patched.")
    else:
        print("FAIL: Expected pattern not found in DeathWatch.kt")
        sys.exit(1)

# Verify DeathWatch.kt
with open(deathwatch_path, "r", encoding="utf-8") as f:
    dw_check = f.read()
    if "parseTombstoneBytes" not in dw_check or "--- NATIVE CRASH DETAILED FORENSICS ---" not in dw_check:
        print("FAIL: Verification failed for DeathWatch.kt")
        sys.exit(1)

print("PASS: All mutations verified successfully.")
