package com.assistant

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlobalCrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logFile = File(context.getExternalFilesDir(null), "crash_log.txt")
            
            FileWriter(logFile, true).use { writer ->
                PrintWriter(writer).use { pw ->
                    pw.println("=== CRASH ANATOMY REPORT: $timestamp ===")
                    pw.println("PROCESS THREAD: ${thread.name}")
                    pw.println("DEVICE: Redmi 15C 4G (HyperOS 3.0)")
                    pw.println("FATAL CAUSE:")
                    exception.printStackTrace(pw)
                    pw.println("=====================================\n")
                }
            }
        } catch (e: Exception) {
            // Failsafe
        } finally {
            defaultHandler?.uncaughtException(thread, exception)
        }
    }
}

// EXTENSION LOGIC APPENDED VIA PROGRAMMATIC SYSTEM RULE
fun bindDiagnosticsInterceptor(context: android.content.Context) {
    // [INJECTED SYSTEM ROUTINE: IPC BOOT]
    com.assistant.core.AdapterIpcBridge.bindAllAdapters(context)

    val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        DiagnosticsEngine.writeCrashLog(context, throwable)
        currentHandler?.uncaughtException(thread, throwable)
    }
}

