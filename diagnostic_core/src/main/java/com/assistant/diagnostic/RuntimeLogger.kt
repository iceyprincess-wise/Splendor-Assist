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
            externalLogFile = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "Splendor_Field_Logs.txt"
            )
        }

        if (forensicDir == null) {

            forensicDir =
                File(
                    "/storage/emulated/0/SplendorAssist/Forensics"
                ).apply {
                    mkdirs()
                }

            executionChainLog =
                File(
                    forensicDir,
                    "execution_chain.log"
                )

            telemetryLog =
                File(
                    forensicDir,
                    "telemetry.log"
                )

            heartbeatLog =
                File(
                    forensicDir,
                    "heartbeat.log"
                )

            fieldTestLog =
                File(
                    forensicDir,
                    "fieldtest.log"
                )
        }

        if (!shouldWriteSessionHeader(context)) {
            return
        }

        val timestamp =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US
            ).format(Date())

        writeToAll(
            "\n=== SESSION START: $timestamp ===\n"
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

    private fun shouldWriteSessionHeader(context: Context): Boolean {
        val processName = currentProcessName()
        return processName.isBlank() || processName == context.packageName
    }

    private fun currentProcessName(): String {
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
            ""
        }
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
        append(
            executionChainLog,
            "${now()} [$stage] $details\n"
        )
    }

    @Synchronized
    fun telemetry(
        details:String
    ){
        append(
            telemetryLog,
            "${now()} $details\n"
        )
    }

    @Synchronized
    fun heartbeat(
        details:String
    ){
        append(
            heartbeatLog,
            "${now()} $details\n"
        )
    }

    @Synchronized
    fun forensic(
        tag:String,
        details:String
    ){
        append(
            fieldTestLog,
            "${now()} [$tag] $details\n"
        )
    }

    private fun now(): String =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        ).format(Date())

    private fun append(
        file:File?,
        text:String
    ){
        try{
            file?.let{
                FileWriter(
                    it,
                    true
                ).use { writer ->
                    writer.append(text)
                }
            }
        }catch(_:Exception){}
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
