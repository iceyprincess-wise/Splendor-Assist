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
        if (segmentDir == null) {
            segmentDir = File(context.filesDir, "runtime_hour_segments").apply { mkdirs() }
        }
        reconcileExpired()

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


    data class HourBucket(
        val hourStart: Long,
        val expiresAt: Long,
        val count: Long
    )

    private const val RETENTION_MS = 10_800_000L
    private const val HOUR_MS = 3_600_000L
    private const val PAGE_SIZE = 250
    private var segmentDir: File? = null

    @Synchronized
    fun reconcileExpired(now: Long = System.currentTimeMillis()) {
        segmentDir?.listFiles()?.forEach { file ->
            val hour = file.name.removePrefix("hour_").removeSuffix(".log").toLongOrNull()
            if (hour != null && now >= hour + HOUR_MS + RETENTION_MS) file.delete()
        }
    }

    @Synchronized
    fun hourBuckets(now: Long = System.currentTimeMillis()): List<HourBucket> {
        reconcileExpired(now)
        return segmentDir?.listFiles()
            ?.mapNotNull { file ->
                val hour = file.name.removePrefix("hour_").removeSuffix(".log").toLongOrNull()
                hour?.let { HourBucket(it, it + HOUR_MS + RETENTION_MS, countLines(file)) }
            }?.filter { now < it.expiresAt }?.sortedByDescending { it.hourStart }.orEmpty()
    }

    @Synchronized
    fun readHourPage(hourStart: Long, page: Int, size: Int = PAGE_SIZE): List<String> {
        reconcileExpired()
        val file = File(segmentDir ?: return emptyList(), "hour_$hourStart.log")
        if (!file.exists()) return emptyList()
        val from = page.coerceAtLeast(0) * size.coerceIn(1, PAGE_SIZE)
        return file.bufferedReader().useLines {
            it.drop(from).take(size.coerceIn(1, PAGE_SIZE)).toList()
        }
    }

    @Synchronized
    fun copyHour(hourStart: Long): String {
        reconcileExpired()
        val file = File(segmentDir ?: return "", "hour_$hourStart.log")
        return if (file.exists()) file.readText() else ""
    }

    @Synchronized
    fun deleteHour(hourStart: Long): Boolean {
        val file = File(segmentDir ?: return false, "hour_$hourStart.log")
        return !file.exists() || file.delete()
    }

    @Synchronized
    fun deleteAllOwnedLogs(): Boolean {
        val segments = segmentDir?.listFiles()?.all { it.delete() } ?: true
        val internal = internalLogFile?.let { !it.exists() || it.delete() } ?: true
        return segments && internal
    }

    private fun countLines(file: File): Long =
        file.bufferedReader().use { reader ->
            var count = 0L
            while (reader.readLine() != null) count++
            count
        }

    private fun appendSegment(text: String, now: Long) {
        val dir = segmentDir ?: return
        val hour = now - now % HOUR_MS
        FileWriter(File(dir, "hour_$hour.log"), true).use { it.append(text) }
    }

    @Synchronized
    fun log(
        message: String,
        tag: String
    ) {

        val timestamp =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US
            ).format(Date())

        val logEntry =
            "$timestamp [$tag] $message\n"

        appendSegment(logEntry, System.currentTimeMillis())
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
