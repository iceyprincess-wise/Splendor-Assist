package com.assistant.diagnostic

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeLogger {
    private const val FILE_NAME = "runtime_diagnostic.txt"
    private var logFile: File? = null

    @Synchronized
    fun initialize(context: Context) {
        if (logFile == null) {
            logFile = File(context.filesDir, FILE_NAME)
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        try {
            FileWriter(logFile, true).use { writer ->
                writer.append("\n=== SESSION START: $timestamp ===\n")
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        log("Application started", "BOOT")
    }

    @Synchronized
    fun log(message: String, tag: String) {
        val file = logFile ?: return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val logEntry = "$timestamp [$tag] $message\n"
        
        try {
            FileWriter(file, true).use { writer ->
                writer.append(logEntry)
            }
        } catch (e: IOException) {
            e.printStackTrace() // Fallback if filesystem write fails
        }
    }
}
