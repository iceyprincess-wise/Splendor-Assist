// Guard header removed under Ed's explicit override for this file only
// (2026-11-12, Patch 4): Ed overrode the guard lock so this Activity's
// views can be bound; it was a setContentView-only shell.
package com.assistant.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.storage.SplendorStorageRoot
import java.io.File

class CrashInspectorActivity : AppCompatActivity() {

    private var logDisplay: TextView? = null
    private var copyButton: Button? = null
    private var prevButton: Button? = null
    private var nextButton: Button? = null

    private var logFiles: List<File> = emptyList()
    private var selectedIndex: Int = 0
    private var displayedContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_inspector)
        try {
            logDisplay = findViewById(R.id.logDisplay)
            copyButton = findViewById(R.id.copyButton)
            prevButton = findViewById(R.id.btnPrevLog)
            nextButton = findViewById(R.id.btnNextLog)
            // Layout sets scrollbars="vertical" only; without a movement
            // method the TextView does not scroll.
            logDisplay?.movementMethod = ScrollingMovementMethod()
            copyButton?.setOnClickListener { copyDisplayedLog() }
            prevButton?.setOnClickListener { selectLog(selectedIndex - 1) }
            nextButton?.setOnClickListener { selectLog(selectedIndex + 1) }
            loadLogFiles()
        } catch (t: Throwable) {
            RuntimeLogger.log("CrashInspectorActivity: bind failed: ${t.message}", "CrashInspectorActivity")
            Toast.makeText(this, R.string.splendor_crash_log_unreadable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        copyButton?.setOnClickListener(null)
        prevButton?.setOnClickListener(null)
        nextButton?.setOnClickListener(null)
        logDisplay = null
        copyButton = null
        prevButton = null
        nextButton = null
        logFiles = emptyList()
        displayedContent = ""
        super.onDestroy()
    }

    // Lists the per-crash files CrashInspector.saveLog writes
    // (crash_log_<millis>.txt under SplendorStorageRoot). The appended
    // mega-file Splendor_Crash_Reports.txt is intentionally not listed:
    // one file per crash is the unit Patch 2 created for listing.
    private fun loadLogFiles() {
        if (!SplendorStorageRoot.isReady()) {
            logFiles = emptyList()
            displayedContent = ""
            logDisplay?.text = getString(R.string.splendor_storage_not_ready)
            RuntimeLogger.log("CrashInspectorActivity: storage not ready, no crash logs listed", "CrashInspectorActivity")
            updateNavButtons()
            return
        }
        try {
            val dir = SplendorStorageRoot.directory()
            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash_log_") && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            logFiles = files
            if (files.isEmpty()) {
                displayedContent = ""
                logDisplay?.text = getString(R.string.splendor_no_crash_logs)
            } else {
                selectedIndex = 0
                showLog(0)
            }
        } catch (t: Throwable) {
            RuntimeLogger.log("CrashInspectorActivity: list failed: ${t.message}", "CrashInspectorActivity")
            logDisplay?.text = getString(R.string.splendor_crash_log_unreadable)
        }
        updateNavButtons()
    }

    private fun selectLog(index: Int) {
        if (logFiles.isEmpty()) return
        try {
            showLog(index)
        } catch (t: Throwable) {
            RuntimeLogger.log("CrashInspectorActivity: show failed: ${t.message}", "CrashInspectorActivity")
            Toast.makeText(this, R.string.splendor_crash_log_unreadable, Toast.LENGTH_SHORT).show()
        }
        updateNavButtons()
    }

    private fun showLog(index: Int) {
        if (logFiles.isEmpty()) return
        selectedIndex = index.coerceIn(0, logFiles.size - 1)
        val file = logFiles[selectedIndex]
        try {
            // Bounded read: at most 256 KB, so a huge report cannot OOM
            // the Activity. Copy copies exactly what was read.
            val capBytes = 256 * 1024
            val bytes = file.inputStream().use { stream ->
                val buffer = ByteArray(capBytes)
                val read = stream.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
            val truncatedBytes = file.length() > capBytes
            val full = String(bytes, Charsets.UTF_8)
            displayedContent = full
            val lines = full.lines()
            val maxLines = 2000
            val shown = if (lines.size > maxLines) lines.take(maxLines) else lines
            val header = getString(R.string.splendor_crash_log_position, selectedIndex + 1, logFiles.size, file.name)
            val notes = buildString {
                if (lines.size > maxLines) append("\n").append(getString(R.string.splendor_crash_truncated_lines, maxLines, lines.size))
                if (truncatedBytes) append("\n").append(getString(R.string.splendor_crash_truncated_bytes, file.length() / 1024))
            }
            logDisplay?.text = header + "\n\n" + shown.joinToString("\n") + notes
        } catch (t: Throwable) {
            RuntimeLogger.log("CrashInspectorActivity: read failed for ${file.name}: ${t.message}", "CrashInspectorActivity")
            logDisplay?.text = getString(R.string.splendor_crash_log_unreadable)
        }
    }

    private fun copyDisplayedLog() {
        if (displayedContent.isEmpty()) {
            Toast.makeText(this, R.string.splendor_no_crash_logs, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                RuntimeLogger.log("CrashInspectorActivity: no clipboard service", "CrashInspectorActivity")
                Toast.makeText(this, R.string.splendor_copy_failed, Toast.LENGTH_SHORT).show()
                return
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Splendor crash log", displayedContent))
            Toast.makeText(this, R.string.splendor_crash_log_copied, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            RuntimeLogger.log("CrashInspectorActivity: copy failed: ${t.message}", "CrashInspectorActivity")
            Toast.makeText(this, R.string.splendor_copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNavButtons() {
        prevButton?.isEnabled = selectedIndex > 0
        nextButton?.isEnabled = selectedIndex < logFiles.size - 1
    }
}
