// Guard header removed under Ed's explicit override for this file only
// (2026-11-12, Patch 4): Ed overrode the guard lock so this Activity's
// views can be bound; it was a setContentView-only shell.
package com.assistant.diagnostic

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.storage.SplendorStorageRoot

class AnalyticsTheaterActivity : AppCompatActivity() {

    // L14-D1 (no42): renamed from matchHistoryList — the list shows live
    // adapter-health analytics, not match history, and the old name lied
    // about the content. Renamed together with the layout ID
    // (@+id/adapterHealthList) and this findViewById call; renaming the
    // layout ID alone was proven to make findViewById return null and
    // silently kill the list.
    private var adapterHealthList: ListView? = null
    private var saveButton: Button? = null
    private var deleteButton: Button? = null

    private var currentLines: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics_theater)
        try {
            adapterHealthList = findViewById(R.id.adapterHealthList)
            saveButton = findViewById(R.id.btnSave)
            deleteButton = findViewById(R.id.btnDelete)
            saveButton?.setOnClickListener { saveSnapshot() }
            deleteButton?.setOnClickListener { deleteSnapshots() }
            loadAnalytics()
        } catch (t: Throwable) {
            RuntimeLogger.log("AnalyticsTheaterActivity: bind failed: ${t.message}", "AnalyticsTheaterActivity")
            Toast.makeText(this, R.string.splendor_analytics_load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        saveButton?.setOnClickListener(null)
        deleteButton?.setOnClickListener(null)
        adapterHealthList?.adapter = null
        adapterHealthList = null
        saveButton = null
        deleteButton = null
        currentLines = emptyList()
        super.onDestroy()
    }

    // The list is live adapter-health analytics. No match-history data
    // source exists anywhere in the repo (only this layout's view ID
    // mentioned MatchHistory, renamed in no42), so nothing here is a
    // match record.
    private fun loadAnalytics() {
        // no42: getAllLive() re-reads the persistence file on every call,
        // so the registry read runs on a background thread — the old code
        // called it from onCreate (main thread) and did file I/O there.
        // Only the read is off-thread; line formatting and the adapter
        // swap stay on the main thread, and a load that finishes after
        // the Activity is gone is dropped. The load is user-initiated
        // (screen open / after a delete), never a frame path, so one
        // short-lived thread per load is enough; no polling.
        Thread {
            val snapshots: List<AdapterHealthSnapshot>? = try {
                AdapterHealthRegistry.getAllLive()
            } catch (t: Throwable) {
                RuntimeLogger.log("AnalyticsTheaterActivity: load failed: ${t.message}", "AnalyticsTheaterActivity")
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    currentLines = if (snapshots == null) {
                        listOf(getString(R.string.splendor_analytics_load_failed))
                    } else {
                        formatLines(snapshots)
                    }
                    adapterHealthList?.adapter =
                        ArrayAdapter(this, android.R.layout.simple_list_item_1, currentLines)
                } catch (t: Throwable) {
                    RuntimeLogger.log("AnalyticsTheaterActivity: load failed: ${t.message}", "AnalyticsTheaterActivity")
                    currentLines = listOf(getString(R.string.splendor_analytics_load_failed))
                    adapterHealthList?.adapter =
                        ArrayAdapter(this, android.R.layout.simple_list_item_1, currentLines)
                }
            }
        }.start()
    }

    // Row text lives in resources (CrashInspectorActivity pattern: every
    // user-visible string via getString). New strings: splendor_analytics_row,
    // splendor_analytics_age, splendor_analytics_age_never.
    private fun formatLines(snapshots: List<AdapterHealthSnapshot>): List<String> {
        if (snapshots.isEmpty()) {
            return listOf(getString(R.string.splendor_no_adapter_data))
        }
        val now = System.currentTimeMillis()
        return snapshots.sortedBy { it.adapterName }.map { snap ->
            val status = AdapterHealthRegistry.effectiveStatus(snap)
            // Future-heartbeat age edge (batch 5): a heartbeat stamped
            // ahead of the clock (clock jump) used to print a negative
            // age; clamp at 0 — a future beat is 0s old, never negative.
            val ageSec = if (snap.lastHeartbeat > 0) ((now - snap.lastHeartbeat) / 1000).coerceAtLeast(0L) else -1
            val age = if (ageSec >= 0) getString(R.string.splendor_analytics_age, ageSec) else getString(R.string.splendor_analytics_age_never)
            getString(R.string.splendor_analytics_row, status, snap.adapterName, age, snap.errorCount)
        }
    }

    // Save writes the currently displayed lines to a new file
    // analytics_snapshot_<millis>.txt under SplendorStorageRoot,
    // the same pattern as CrashInspector.saveLog.
    // Main-thread is deliberate (batch 5 note): save/delete are
    // tap-initiated, tiny (one writeText of the displayed lines / one
    // delete loop over small snapshot files), and the load path is
    // already off-thread — moving these would add a thread hop to a
    // user tap for no measurable gain.
    private fun saveSnapshot() {
        if (!SplendorStorageRoot.isReady()) {
            RuntimeLogger.log("AnalyticsTheaterActivity: save dropped, storage not ready", "AnalyticsTheaterActivity")
            Toast.makeText(this, R.string.splendor_storage_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // no42: one timestamp for the file name and the header (the old
            // code called currentTimeMillis() twice, so the two could differ
            // by a few ms). Header text is a resource now.
            val nowMs = System.currentTimeMillis()
            val name = "analytics_snapshot_${nowMs}.txt"
            val file = SplendorStorageRoot.file(name)
            val header = getString(R.string.splendor_analytics_snapshot_header, nowMs)
            file.writeText(header + currentLines.joinToString("\n"))
            Toast.makeText(this, R.string.splendor_analytics_saved, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            RuntimeLogger.log("AnalyticsTheaterActivity: save failed: ${t.message}", "AnalyticsTheaterActivity")
            Toast.makeText(this, R.string.splendor_analytics_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // Delete removes only the saved analytics_snapshot_*.txt files.
    // It never touches live adapter data and never clears the list;
    // the list is reloaded unchanged afterwards.
    private fun deleteSnapshots() {
        if (!SplendorStorageRoot.isReady()) {
            RuntimeLogger.log("AnalyticsTheaterActivity: delete dropped, storage not ready", "AnalyticsTheaterActivity")
            Toast.makeText(this, R.string.splendor_storage_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = SplendorStorageRoot.directory()
            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("analytics_snapshot_") && it.name.endsWith(".txt") }
                ?: emptyList()
            var deleted = 0
            for (f in files) {
                if (f.delete()) deleted++
            }
            if (files.isEmpty()) {
                Toast.makeText(this, R.string.splendor_analytics_nothing_to_delete, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.splendor_analytics_deleted, deleted, files.size), Toast.LENGTH_SHORT).show()
            }
            RuntimeLogger.log("AnalyticsTheaterActivity: delete removed $deleted of ${files.size} saved snapshot(s)", "AnalyticsTheaterActivity")
            loadAnalytics()
        } catch (t: Throwable) {
            RuntimeLogger.log("AnalyticsTheaterActivity: delete failed: ${t.message}", "AnalyticsTheaterActivity")
            Toast.makeText(this, R.string.splendor_analytics_delete_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
