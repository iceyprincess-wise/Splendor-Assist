package com.assistant.diagnostic

import com.assistant.storage.SplendorStorageRoot

// Guard header removed under Ed's explicit override for this file only
// (2026-11-12): the file was dead and unguarded and needed fixing.
object CrashInspector {
    // Canonical storage root lives in core (SplendorStorageRoot.ROOT_PATH
    // is "/sdcard/Splendor-Assist"); directory creation is its
    // initialize() job, called at App start. The old hardcoded LOG_DIR
    // val and unchecked init mkdirs() are gone.
    /**
     * Writes one per-crash file, crash_log_<millis>.txt, under the
     * canonical Splendor storage root. Never throws: returns false
     * (and logs the failure) when storage is not ready or the write
     * fails, so a caller on the crash path can never be killed by
     * the logger itself.
     */
    fun saveLog(content: String): Boolean {
        return try {
            if (!SplendorStorageRoot.isReady()) {
                RuntimeLogger.log(
                    "CrashInspector: storage not ready, dropping crash log (${content.length} chars)",
                    "CrashInspector"
                )
                return false
            }
            val file = SplendorStorageRoot.file("crash_log_${System.currentTimeMillis()}.txt")
            file.writeText(content)
            true
        } catch (e: Throwable) {
            RuntimeLogger.log("CrashInspector: saveLog failed: ${e.message}", "CrashInspector")
            false
        }
    }
}
