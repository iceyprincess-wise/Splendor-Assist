package com.assistant.storage

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
