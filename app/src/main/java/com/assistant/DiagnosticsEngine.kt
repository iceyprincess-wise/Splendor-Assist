package com.assistant

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale

object DiagnosticsEngine {
    // UPGRADE: Moved from volatile cacheDir to canonical persistent storage.
    // Prevents LMK from deleting crash reports on 4GB RAM devices before user can read them.
    private const val CRASH_FILE_NAME = "java_crash_report.txt"
    private var sessionStartTime: Long = 0

    @Volatile private var isInitialized = false

    @Synchronized
    fun initTracking() {
        if (isInitialized) return
        sessionStartTime = SystemClock.elapsedRealtime()
        isInitialized = true
    }

    @Synchronized
    fun writeCrashLog(context: Context, throwable: Throwable) {
        if (!SplendorStorageRoot.isReady()) return

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(System.currentTimeMillis())
        val systemReport = buildString {
            appendLine("=======================================")
            appendLine("JAVA/KOTLIN EXCEPTION REPORT")
            appendLine("=======================================")
            appendLine("Timestamp: $timestamp")
            appendLine("Thread: ${Thread.currentThread().name}")
            appendLine("Hardware: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("OS API Level: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("---------------------------------------")
            appendLine("STACK TRACE:")
            append(sw.toString())
            appendLine("=======================================")
        }
        
        try {
            val file = SplendorStorageRoot.file(CRASH_FILE_NAME)
            // Append rather than overwrite to preserve crash history across sessions
            file.appendText(systemReport)
            
            // Signal DeathWatch that a Java crash occurred in this session
            val markerDir = SplendorStorageRoot.subdirectory("deathwatch")
            val marker = File(markerDir, "java-crash.marker")
            marker.writeText("timestamp=${System.currentTimeMillis()}|pid=${android.os.Process.myPid()}")
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun readCrashLog(): String {
        return try {
            if (!SplendorStorageRoot.isReady()) return "Storage not ready."
            val file = SplendorStorageRoot.file(CRASH_FILE_NAME)
            if (file.exists()) file.readText() else "No Java crash records observed."
        } catch (_: Throwable) {
            "Error reading crash log."
        }
    }

    @Synchronized
    fun clearCrashLog() {
        try {
            if (!SplendorStorageRoot.isReady()) return
            val file = SplendorStorageRoot.file(CRASH_FILE_NAME)
            if (file.exists()) file.delete()
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun getRuntimeReport(context: Context): String {
        val currentUptime = (SystemClock.elapsedRealtime() - sessionStartTime) / 1000
        val runtimeHours = currentUptime / 3600
        val runtimeMinutes = (currentUptime % 3600) / 60
        val runtimeSeconds = currentUptime % 60

        // UPGRADE: Replaced useless JVM heap metrics with actual device memory metrics.
        // eFootball 2027 is native C++. JVM stats do not reflect LMK risk on 4GB RAM.
        val memInfo = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(memInfo)
        
        val availMb = memInfo.availMem / 1048576L
        val totalMb = memInfo.totalMem / 1048576L
        val thresholdMb = memInfo.threshold / 1048576L

        return buildString {
            appendLine("Execution Duration: ${runtimeHours}h ${runtimeMinutes}m ${runtimeSeconds}s")
            appendLine("Device RAM Total: ${totalMb}MB")
            appendLine("Device RAM Available: ${availMb}MB")
            appendLine("LMK Threshold: ${thresholdMb}MB")
            appendLine("Low Memory State: ${memInfo.lowMemory}")
        }
    }
}
