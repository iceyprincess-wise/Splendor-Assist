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
            // No42-D1: findViewById returns null, it never throws, so a
            // missing layout id used to slip straight past this try/catch
            // and leave a blank Activity with no error at all (every later
            // use is a safe call). Fail loudly instead: log which views are
            // missing, tell the user, and stop before loading any logs.
            val missingViews = listOf(
                "logDisplay" to logDisplay,
                "copyButton" to copyButton,
                "btnPrevLog" to prevButton,
                "btnNextLog" to nextButton
            ).filter { it.second == null }.map { it.first }
            if (missingViews.isNotEmpty()) {
                RuntimeLogger.log(
                    "CrashInspectorActivity: bind failed: missing views: ${missingViews.joinToString(", ")}",
                    "CrashInspectorActivity"
                )
                Toast.makeText(this, R.string.splendor_crash_log_unreadable, Toast.LENGTH_SHORT).show()
                return
            }
            // Layout sets scrollbars="vertical" only; without a movement
            // method the TextView does not scroll.
            logDisplay?.movementMethod = ScrollingMovementMethod()
            copyButton?.setOnClickListener { copyDisplayedLog() }
            prevButton?.setOnClickListener { selectLog(selectedIndex - 1) }
            nextButton?.setOnClickListener { selectLog(selectedIndex + 1) }
            loadLogFiles()
        } catch (t: Throwable) {
            // Near-dead in practice (findViewById does not throw and
            // loadLogFiles catches its own failures), but retained as the
            // safety net for setContentView inflation failures, which do
            // throw. Do not remove: the cost is one catch block.
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
        selectedIndex = 0
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
            selectedIndex = 0
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
                // No42-D2: selectedIndex was left at its old value here, so
                // after a reload that empties the list the Prev button
                // stayed enabled (selectedIndex > 0) with nothing to show.
                // Only reachable on a reload today (load runs once in
                // onCreate, where the index starts at 0), but the reset
                // costs one line and makes the empty state always coherent.
                selectedIndex = 0
                displayedContent = ""
                logDisplay?.text = getString(R.string.splendor_no_crash_logs)
            } else {
                selectedIndex = 0
                showLog(0)
            }
        } catch (t: Throwable) {
            // No42-D2 (same fix, failure arm): a listing failure used to
            // leave a stale logFiles/selection behind while the display
            // said "unreadable". Clear both so state matches the screen.
            logFiles = emptyList()
            selectedIndex = 0
            displayedContent = ""
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
            // Near-dead: showLog coerces the index and catches its own
            // read failures, so this only fires for an unexpected throw
            // (e.g. getString). Retained as a safety net, as in onCreate.
            RuntimeLogger.log("CrashInspectorActivity: show failed: ${t.message}", "CrashInspectorActivity")
            Toast.makeText(this, R.string.splendor_crash_log_unreadable, Toast.LENGTH_SHORT).show()
        }
        updateNavButtons()
    }

    private fun showLog(index: Int) {
        if (logFiles.isEmpty()) return
        // No bounds check is needed here: coerceIn clamps out-of-range
        // Prev/Next taps (selectedIndex - 1 / + 1) to a valid slot, so
        // selectLog passing an unclamped index is safe by construction.
        selectedIndex = index.coerceIn(0, logFiles.size - 1)
        val file = logFiles[selectedIndex]
        try {
            // Bounded read: at most 256 KB, so a huge report cannot OOM
            // the Activity. Copy copies exactly what is displayed.
            val capBytes = 256 * 1024
            // No42-D3: a single InputStream.read() is NOT guaranteed to
            // fill the buffer (short reads are legal), so the old code
            // could silently show less than the cap. Loop until the cap
            // is full or EOF.
            val buffer = ByteArray(capBytes)
            var readTotal = 0
            file.inputStream().use { stream ->
                while (readTotal < capBytes) {
                    val read = stream.read(buffer, readTotal, capBytes - readTotal)
                    if (read <= 0) break
                    readTotal += read
                }
            }
            val bytes = buffer.copyOf(readTotal)
            // No42-D4 / S12-D1: file.length() was called twice (once for
            // the truncation test, once inside the note), so the note
            // could disagree with the test if the file changed between
            // the calls. Capture the length once. The note's total also
            // used floor division: a 262,200-byte file printed "showing
            // first 256 KB of 256 KB" while truncatedBytes was true. The
            // total now rounds UP, and the cap is a format arg, not
            // hardcoded text (see strings.xml): "first %1$d KB of %2$d KB".
            val fileBytes = file.length()
            val truncatedBytes = fileBytes > capBytes
            val capKb = capBytes / 1024
            val totalKb = (fileBytes + 1023L) / 1024L
            val full = String(bytes, Charsets.UTF_8)
            val lines = full.lines()
            val maxLines = 2000
            val shown = if (lines.size > maxLines) lines.take(maxLines) else lines
            val header = getString(R.string.splendor_crash_log_position, selectedIndex + 1, logFiles.size, file.name)
            val notes = buildString {
                if (lines.size > maxLines) append("\n").append(getString(R.string.splendor_crash_truncated_lines, maxLines, lines.size))
                if (truncatedBytes) append("\n").append(getString(R.string.splendor_crash_truncated_bytes, capKb, totalKb))
            }
            // No42-D5: displayedContent used to hold the capped raw bytes
            // (all lines, no header, no truncation notes) while the screen
            // showed header + first 2000 lines + notes, so "Copy Crash
            // Log" copied something the user never saw. The displayed
            // text and the copied text are now the same string.
            val displayText = header + "\n\n" + shown.joinToString("\n") + notes
            displayedContent = displayText
            logDisplay?.text = displayText
        } catch (t: Throwable) {
            RuntimeLogger.log("CrashInspectorActivity: read failed for ${file.name}: ${t.message}", "CrashInspectorActivity")
            logDisplay?.text = getString(R.string.splendor_crash_log_unreadable)
        }
    }

    private fun copyDisplayedLog() {
        // No42-D5 (guard arm): the old empty check read displayedContent,
        // which is now the full displayed text (header included), so an
        // existing-but-empty log file would have copied a bare header.
        // The guard is on the list instead: nothing listed, nothing to copy.
        if (logFiles.isEmpty()) {
            Toast.makeText(this, R.string.splendor_no_crash_logs, Toast.LENGTH_SHORT).show()
            return
        }
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
