/* [SECURITY GUARD LOCK ACTIVE] - IMMUTABLE - DO NOT MODIFY */
package com.assistant.diagnostic
import java.io.File
object CrashInspector {
    val LOG_DIR = File("/sdcard/Splendor Assist/data/logs")
    init { if (!LOG_DIR.exists()) LOG_DIR.mkdirs() }
    fun saveLog(content: String) {
        val file = File(LOG_DIR, "crash_log_${System.currentTimeMillis()}.txt")
        file.writeText(content)
    }
}
