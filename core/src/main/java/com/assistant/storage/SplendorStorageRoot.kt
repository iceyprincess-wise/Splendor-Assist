package com.assistant.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

/*
 * Canonical storage root for Splendor-Assist files (crash logs, field
 * logs, deathwatch markers, analytics snapshots, telemetry).
 *
 * Below Android R (minSdk 26-29) there is no MANAGE_EXTERNAL_STORAGE /
 * All Files Access path: initialize() can only fail there, and the app
 * runs in degraded mode without storage (accepted - S17-D6; raising
 * minSdk or adding a legacy permission is a build-file decision, not
 * in this round).
 *
 * Lazy retry (S17-D1): initialize() runs once at App.onCreate and is
 * never re-run after the All Files grant, so directory() retries it at
 * the point of use when not ready yet. isReady() stays a pure check
 * (no retry in it). The retry is cheap when the gate fails:
 * initialize() checks the permission gate first and fails fast.
 * NOTE: this does NOT fix DeathWatch.install() calling subdirectory()
 * unguarded from bare App.onCreate() (S17-D3, open) - install runs at
 * onCreate, before the permission flow, so no lazy retry can help it;
 * that fix belongs in DeathWatch.kt / App.kt.
 *
 * LOGFOLDER fresh-install reset (2026-11-24): initialize() wipes the
 * shared root on first run after a fresh install (marker in
 * noBackupFilesDir, which Android deletes on uninstall and never
 * restores from backup) and recreates it empty, so the folder holds
 * only the current install's logs. See resetIfFreshInstall().
 */
object SplendorStorageRoot {

    private const val TAG = "SplendorStorageRoot"

    /*
     * Display string for the canonical root, kept for callers that show
     * it to the user (DeathWatch tombstone notes). The live root is
     * resolved from Environment.getExternalStorageDirectory() (S17-D7):
     * "/sdcard" is only a symlink to the primary external storage, and
     * the hardcode breaks on devices where it is not.
     */
    const val ROOT_PATH = "/sdcard/Splendor-Assist"

    private val root by lazy {
        File(Environment.getExternalStorageDirectory(), "Splendor-Assist")
    }

    @Volatile
    private var ready = false

    @Synchronized
    fun initialize(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            ready = false
            return false
        }

        // LOGFOLDER fresh-install reset: runs before the root is created
        // or probed, and on every initialize() until the marker is
        // written, so the grant-later path (MainActivity calls
        // initialize() after the All Files grant) still gets a fresh
        // folder. Fail-open: a reset failure never blocks initialize().
        try {
            resetIfFreshInstall()
        } catch (e: Throwable) {
            if (e is Error) throw e
            Log.w(TAG, "fresh-install reset failed; continuing with existing folder", e)
        }

        return try {
            if (!root.exists() && !root.mkdirs()) {
                ready = false
                return false
            }

            if (!root.isDirectory || !root.canWrite()) {
                ready = false
                return false
            }

            // Per-process probe name (S17-D4): two processes initialising
            // at once must not delete each other's probe, and the probe
            // name must not collide across processes.
            val probe = File(root, ".storage_probe_${android.os.Process.myPid()}")

            val valid: Boolean
            try {
                probe.writeText("splendor-storage-ready")

                valid = probe.isFile && probe.length() > 0L
            } finally {
                if (!probe.delete() && probe.exists()) {
                    Log.w(TAG, "storage probe delete failed: ${probe.absolutePath}")
                }
            }

            ready = valid
            valid
        } catch (e: Throwable) {
            // S17-D5: Errors propagate (they are not ours to swallow);
            // ordinary failures are logged with their cause instead of
            // vanishing silently. The return-false contract is unchanged.
            if (e is Error) throw e
            Log.e(TAG, "storage initialize failed", e)
            ready = false
            false
        }
    }

    /*
     * LOGFOLDER fresh-install reset (2026-11-24, Ed's order): the root
     * lives in SHARED external storage (/sdcard/Splendor-Assist), so
     * Android does NOT delete it on uninstall — every reinstall kept
     * appending to the previous install's logs (~100 GB on Ed's phone,
     * old and new indistinguishable). The marker lives in
     * noBackupFilesDir (internal storage, deleted on uninstall and
     * excluded from Auto Backup), so its absence means "first run
     * after a fresh install". On a fresh install the old root is moved
     * aside (atomic rename — no ANR even on a ~100 GB folder) and a
     * fresh root is created at once; the old tree is deleted on a
     * background thread. The folder stays in SD-card storage (not
     * Downloads); only its contents are reset. Fail-open throughout:
     * any failure logs and boot continues with the existing folder.
     */
    private const val INSTALL_MARKER_NAME = ".install_marker_v1"
    private const val TRASH_DIR_NAME = "Splendor-Assist.old"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var resetDone = false

    /**
     * Retains the application context for the fresh-install reset.
     * Called once from App.onCreate; the application context is
     * process-lifetime so this cannot leak an Activity. A null store
     * just means the reset is skipped (fail-open).
     */
    @Synchronized
    fun attach(context: Context) {
        if (appContext == null) {
            try {
                appContext = context.applicationContext
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Wipe the shared log folder once per fresh install and recreate it
     * empty. Safe to call on every process start and from initialize():
     * once the marker is written this latches and becomes a no-op.
     * Returns true when the folder is fresh (marker present or just
     * written), false when the reset was deferred or failed — the app
     * must never block on the result.
     */
    @Synchronized
    fun resetIfFreshInstall(): Boolean {
        if (resetDone) return true
        val context = appContext ?: return false
        val done = try {
            resetIfFreshInstallInternal(context)
        } catch (e: Throwable) {
            // S17-D5 convention: Errors propagate, ordinary failures log.
            if (e is Error) throw e
            Log.w(TAG, "fresh-install reset failed; continuing with existing folder", e)
            false
        }
        if (done) resetDone = true
        return done
    }

    private fun resetIfFreshInstallInternal(context: Context): Boolean {
        val marker = try {
            File(markerDir(context), INSTALL_MARKER_NAME)
        } catch (_: Throwable) {
            return false
        }

        if (marker.isFile) {
            // Not a fresh install. Sweep trash dirs left by an
            // interrupted earlier reset so they never linger in the
            // file manager.
            deleteStaleTrashInBackground()
            return true
        }

        val wipeStartMs = System.currentTimeMillis()

        // Fast path: rename the old root aside (atomic — no ANR even on
        // a ~100 GB folder), recreate a fresh root immediately, delete
        // the old tree on a background thread.
        var renamed = false
        try {
            if (!root.exists()) {
                renamed = true
            } else {
                val trash = trashDirForThisRun()
                if (trash != null) {
                    renamed = root.renameTo(trash)
                    if (renamed) deleteInBackground(trash)
                }
            }
        } catch (_: Throwable) {
            renamed = false
        }

        if (!renamed) {
            // Fallback: the rename failed (e.g. cross-device). Delete
            // old entries on a background thread, keeping anything
            // written after this reset started, and write the marker
            // when the sweep finishes — never before, so a crash
            // mid-sweep retries the wipe on the next launch.
            deleteOldEntriesInBackground(wipeStartMs, marker)
            Log.i(TAG, "fresh-install reset running in background (rename unavailable)")
            return false
        }

        try {
            if (!root.exists() && !root.mkdirs()) {
                Log.w(TAG, "fresh-install reset: could not recreate ${root.absolutePath}")
                return false
            }
        } catch (_: Throwable) {
            return false
        }

        // Marker LAST: only a completed fresh root earns it, so a crash
        // mid-reset retries the wipe on the next launch.
        return try {
            marker.writeText("installed=$wipeStartMs\n")
            Log.i(TAG, "LOGFOLDER fresh-install reset: ${root.absolutePath} recreated fresh")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun markerDir(context: Context): File {
        // noBackupFilesDir is excluded from Auto Backup (unlike
        // filesDir), so a restored backup can never resurrect the
        // marker on a fresh install and defeat the reset. Falls back
        // to filesDir if the no-backup dir is unavailable.
        return try {
            context.noBackupFilesDir ?: context.filesDir
        } catch (_: Throwable) {
            context.filesDir
        }
    }

    private fun trashDirForThisRun(): File? {
        // Per-process trash name: two processes resetting at once must
        // not collide on the rename target (S17-D4, same reason as the
        // probe name).
        return try {
            root.parentFile?.let { File(it, "$TRASH_DIR_NAME-${android.os.Process.myPid()}") }
        } catch (_: Throwable) {
            null
        }
    }

    private fun deleteStaleTrashInBackground() {
        try {
            val parent = root.parentFile ?: return
            val stale = try {
                parent.listFiles()?.filter { it.isDirectory && it.name.startsWith(TRASH_DIR_NAME) }
            } catch (_: Throwable) {
                null
            } ?: return
            for (dir in stale) deleteInBackground(dir)
        } catch (_: Throwable) {
        }
    }

    private fun deleteInBackground(dir: File?) {
        if (dir == null) return
        try {
            Thread({
                try {
                    dir.deleteRecursively()
                } catch (_: Throwable) {
                }
            }, "splendor-storage-reset").apply { isDaemon = true; start() }
        } catch (_: Throwable) {
        }
    }

    private fun deleteOldEntriesInBackground(wipeStartMs: Long, marker: File) {
        try {
            Thread({
                try {
                    deleteOlderThan(root, wipeStartMs)
                } catch (_: Throwable) {
                }
                try {
                    marker.writeText("installed=$wipeStartMs\n")
                } catch (_: Throwable) {
                }
            }, "splendor-storage-reset").apply { isDaemon = true; start() }
        } catch (_: Throwable) {
        }
    }

    private fun deleteOlderThan(dir: File, cutoffMs: Long) {
        val children = try {
            dir.listFiles()
        } catch (_: Throwable) {
            return
        } ?: return
        for (child in children) {
            try {
                if (child.lastModified() >= cutoffMs) continue
                if (child.isDirectory) {
                    deleteOlderThan(child, cutoffMs)
                    child.delete()
                } else {
                    child.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun isReady(): Boolean {
        return ready && root.isDirectory && root.canWrite()
    }

    fun directory(): File {
        // Lazy retry (S17-D1): see the header. initialize() never calls
        // directory(), so this cannot recurse.
        if (!ready) initialize()
        // Check-then-use is best-effort, not atomic (S17-D8): storage can
        // still be revoked between this check and the caller's use, so
        // callers must stay inside try/catch - already the convention
        // (RuntimeLogger, CrashForensics, FilterEngine all catch).
        check(isReady()) {
            "Splendor storage is not ready: ${root.absolutePath}"
        }

        return root
    }

    private fun requireContained(child: File) {
        // Belt-and-braces containment (S17-D2): the child must resolve
        // inside the root even after symlinks are resolved.
        val rootCanonical = root.canonicalPath
        val childCanonical = child.canonicalPath
        require(childCanonical == rootCanonical ||
            childCanonical.startsWith(rootCanonical + File.separator)) {
            "Storage path escapes the storage root: ${child.absolutePath}"
        }
    }

    fun file(name: String): File {
        require(name.isNotBlank()) {
            "Storage filename must not be blank"
        }

        require('/' !in name && '\\' !in name) {
            "Storage filename must remain directly under ${root.absolutePath}"
        }

        // S17-D2: ".." escapes the root (proven by a real File run:
        // File(root, "..").canonicalPath is the parent of the root).
        require(name != "." && name != "..") {
            "Storage filename must not escape ${root.absolutePath}"
        }

        val child = File(directory(), name)
        requireContained(child)
        return child
    }

    fun subdirectory(name: String): File {
        require(name.isNotBlank()) {
            "Storage directory name must not be blank"
        }

        require('/' !in name && '\\' !in name) {
            "Storage directory must remain directly under ${root.absolutePath}"
        }

        require(name != "." && name != "..") {
            "Storage directory must not escape ${root.absolutePath}"
        }

        val child = File(directory(), name)
        requireContained(child)

        if (!child.exists() && !child.mkdirs()) {
            error("Unable to create storage directory: ${child.absolutePath}")
        }

        check(child.isDirectory) {
            "Storage path is not a directory: ${child.absolutePath}"
        }

        return child
    }
}
