package com.assistant.diagnostic

import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.FileOutputStream

// Guard header removed under Ed's explicit override for this file only
// (2026-11-12): the file was dead and unguarded and needed fixing.
object CrashInspector {
    // Canonical storage root lives in core (SplendorStorageRoot.ROOT_PATH
    // is "/sdcard/Splendor-Assist"); directory creation is its
    // initialize() job, called at App start. The old hardcoded LOG_DIR
    // val and unchecked init mkdirs() are gone.

    // No42-D7: per-crash files used to accumulate forever (nothing
    // anywhere pruned crash_log_*.txt). Keep the 20 newest; older files
    // are deleted best-effort after a successful save. 20 keeps a useful
    // crash history on the device without letting the folder grow
    // without bound; the appended mega-file Splendor_Crash_Reports.txt
    // is untouched (it is the permanent record, not a per-crash copy).
    private const val MAX_KEPT_LOGS = 20

    /**
     * Writes one per-crash file, crash_log_<millis>.txt, under the
     * canonical Splendor storage root. Never throws: returns false
     * (and logs the failure) when storage is not ready or the write
     * fails, so a caller on the crash path can never be killed by
     * the logger itself.
     *
     * The write is atomic: content goes to a uniquely-named temp file
     * in the same directory, is fsynced, and is then renamed onto its
     * final name, so a kill mid-write can never leave a truncated
     * crash_log_*.txt behind. The final name is unique even for two
     * crashes in the same millisecond (a numeric suffix is appended
     * while the candidate name exists), so the second crash no longer
     * overwrites the first.
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
            val target = uniqueTarget()
            val tmp = SplendorStorageRoot.file(
                "crash_log_${System.currentTimeMillis()}_${System.nanoTime()}.tmp"
            )
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                    // Crash path: the process is about to be killed by the
                    // default handler, so push the bytes to storage now
                    // instead of trusting the page cache alone.
                    try { out.fd.sync() } catch (_: Throwable) { }
                }
                if (!tmp.renameTo(target)) {
                    // Lost a same-millisecond race after the uniqueness
                    // check: pick a fresh name once and retry. If that
                    // also fails, drop the copy rather than overwrite.
                    val retryTarget = uniqueTarget()
                    if (!tmp.renameTo(retryTarget)) {
                        tmp.delete()
                        RuntimeLogger.log(
                            "CrashInspector: saveLog failed: could not rename temp crash log",
                            "CrashInspector"
                        )
                        return false
                    }
                }
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
            pruneOldLogs()
            true
        } catch (e: Throwable) {
            RuntimeLogger.log("CrashInspector: saveLog failed: ${e.message}", "CrashInspector")
            false
        }
    }

    /**
     * Picks a final name that does not exist yet: crash_log_<millis>.txt,
     * then crash_log_<millis>_1.txt, _2.txt, ... while the candidate is
     * taken. Two crashes in the same millisecond get different files.
     */
    private fun uniqueTarget(): File {
        val base = "crash_log_${System.currentTimeMillis()}"
        var candidate = SplendorStorageRoot.file("$base.txt")
        var suffix = 0
        while (candidate.exists()) {
            suffix++
            candidate = SplendorStorageRoot.file("${base}_$suffix.txt")
        }
        return candidate
    }

    /**
     * Deletes the oldest per-crash files beyond MAX_KEPT_LOGS, newest
     * kept. Best-effort only: any failure is swallowed, never turns a
     * successful save into a failure, and is logged once.
     */
    private fun pruneOldLogs() {
        try {
            val dir = SplendorStorageRoot.directory()
            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash_log_") && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            // Orphaned temp files from a kill between the temp write and
            // the rename (a .tmp is never a listed crash log): remove them
            // so a crash during a crash cannot litter the folder. This
            // runs even when no pruning is due.
            val orphans = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash_log_") && it.name.endsWith(".tmp") }
                ?: emptyList()
            for (orphan in orphans) {
                try { orphan.delete() } catch (_: Throwable) { }
            }
            if (files.size <= MAX_KEPT_LOGS) return
            for (old in files.drop(MAX_KEPT_LOGS)) {
                try { old.delete() } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            RuntimeLogger.log("CrashInspector: prune failed: ${t.message}", "CrashInspector")
        }
    }
}
