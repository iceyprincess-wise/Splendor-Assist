package com.assistant

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale

object DiagnosticsEngine {
    private const val CRASH_FILE_NAME = "crash_report.txt"
    private const val RUNTIME_FILE_NAME = "runtime_metrics.bin"
    private var sessionStartTime: Long = 0

    fun initTracking() {
        sessionStartTime = SystemClock.elapsedRealtime()
    }

    fun writeCrashLog(context: Context, throwable: Throwable) {
        val file = File(context.cacheDir, CRASH_FILE_NAME)
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(System.currentTimeMillis())
        val systemReport = """
            =======================================
            ANATOMICAL CRASH REPORT
            =======================================
            Timestamp: $timestamp
            Thread: ${Thread.currentThread().name}
            Hardware: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
            OS API Level: ${android.os.Build.VERSION.SDK_INT}
            ---------------------------------------
            STACK TRACE:
            $sw
            =======================================
        """.trimIndent()
        
        file.writeText(systemReport)
    }

    fun readCrashLog(context: Context): String {
        val file = File(context.cacheDir, CRASH_FILE_NAME)
        return if (file.exists()) file.readText() else "No crash records observed."
    }

    fun clearCrashLog(context: Context) {
        val file = File(context.cacheDir, CRASH_FILE_NAME)
        if (file.exists()) file.delete()
    }

    fun getRuntimeReport(context: Context): String {
        val currentUptime = (SystemClock.elapsedRealtime() - sessionStartTime) / 1000
        val runtimeHours = currentUptime / 3600
        val runtimeMinutes = (currentUptime % 3600) / 60
        val runtimeSeconds = currentUptime % 60

        val runtimeMem = Runtime.getRuntime()
        val totalMemory = runtimeMem.totalMemory() / (1024 * 1024)
        val freeMemory = runtimeMem.freeMemory() / (1024 * 1024)
        val maxMemory = runtimeMem.maxMemory() / (1024 * 1024)
        val allocatedMemory = totalMemory - freeMemory

        return """
            Execution Duration: ${runtimeHours}h ${runtimeMinutes}m ${runtimeSeconds}s
            JVM Heap Total Allocation: ${allocatedMemory}MB / ${maxMemory}MB
            Active Context Subsystems: 1 core_host
        """.trimIndent()
    }
}
