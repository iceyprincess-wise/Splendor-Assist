package com.assistant.diagnostic

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeLogger {

    private const val FILE_NAME =
        "runtime_diagnostic.txt"

    @Volatile
    private var internalLogFile: File? = null

    @Volatile
    private var externalLogFile: File? = null

    @Synchronized
    fun initialize(context: Context) {

        if (internalLogFile == null) {
            internalLogFile =
                File(
                    context.filesDir,
                    FILE_NAME
                )
        }

        if (externalLogFile == null) {
            externalLogFile =
                context.getExternalFilesDir(null)?.let {
                    File(it, FILE_NAME)
                }
        }

        val timestamp =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US
            ).format(Date())

        writeToAll(
            "\n=== SESSION START: $timestamp ===\n"
        )

        log(
            "Application started",
            "BOOT"
        )
    }

    @Synchronized
    fun log(
        message: String,
        tag: String
    ) {

        val timestamp =
            SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.US
            ).format(Date())

        val logEntry =
            "$timestamp [$tag] $message\n"

        writeToAll(logEntry)
    }

    private fun writeToAll(
        text: String
    ) {

        try {

            internalLogFile?.let { file ->
                FileWriter(
                    file,
                    true
                ).use { writer ->
                    writer.append(text)
                }
            }

            externalLogFile?.let { file ->
                FileWriter(
                    file,
                    true
                ).use { writer ->
                    writer.append(text)
                }
            }

        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
