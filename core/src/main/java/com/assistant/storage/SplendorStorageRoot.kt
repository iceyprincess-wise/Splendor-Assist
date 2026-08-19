package com.assistant.storage

import android.os.Build
import android.os.Environment
import java.io.File

object SplendorStorageRoot {

    const val ROOT_PATH = "/sdcard/Splendor-Assist"

    private val root = File(ROOT_PATH)

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

            val probe = File(root, ".storage_probe")

            probe.writeText("splendor-storage-ready")

            val valid = probe.isFile && probe.length() > 0L

            probe.delete()

            ready = valid
            valid
        } catch (_: Throwable) {
            ready = false
            false
        }
    }

    fun isReady(): Boolean {
        return ready && root.isDirectory && root.canWrite()
    }

    fun directory(): File {
        check(isReady()) {
            "Splendor storage is not ready: $ROOT_PATH"
        }

        return root
    }

    fun file(name: String): File {
        require(name.isNotBlank()) {
            "Storage filename must not be blank"
        }

        require('/' !in name && '\\' !in name) {
            "Storage filename must remain directly under $ROOT_PATH"
        }

        return File(directory(), name)
    }

    fun subdirectory(name: String): File {
        require(name.isNotBlank()) {
            "Storage directory name must not be blank"
        }

        require('/' !in name && '\\' !in name) {
            "Storage directory must remain directly under $ROOT_PATH"
        }

        val child = File(directory(), name)

        if (!child.exists() && !child.mkdirs()) {
            error("Unable to create storage directory: ${child.absolutePath}")
        }

        check(child.isDirectory) {
            "Storage path is not a directory: ${child.absolutePath}"
        }

        return child
    }
}
