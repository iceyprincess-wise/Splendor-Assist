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
import com.assistant.storage.SplendorStorageRoot

class AnalyticsTheaterActivity : AppCompatActivity() {

    private var matchHistoryList: ListView? = null
    private var saveButton: Button? = null
    private var deleteButton: Button? = null

    private var currentLines: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics_theater)
        try {
            matchHistoryList = findViewById(R.id.matchHistoryList)
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
        matchHistoryList?.adapter = null
        matchHistoryList = null
        saveButton = null
        deleteButton = null
        currentLines = emptyList()
        super.onDestroy()
    }

    // The list is live adapter-health analytics. No match-history data
    // source exists anywhere in the repo (only this layout's view ID
    // mentions MatchHistory), so nothing here is a match record.
    private fun loadAnalytics() {
        try {
            val snapshots = AdapterHealthRegistry.getAllLive()
            currentLines = if (snapshots.isEmpty()) {
                listOf(getString(R.string.splendor_no_adapter_data))
            } else {
                val now = System.currentTimeMillis()
                snapshots.sortedBy { it.adapterName }.map { snap ->
                    val status = AdapterHealthRegistry.effectiveStatus(snap)
                    val ageSec = if (snap.lastHeartbeat > 0) (now - snap.lastHeartbeat) / 1000 else -1
                    val age = if (ageSec >= 0) "age ${ageSec}s" else "never"
                    "[$status] ${snap.adapterName} — $age, errors ${snap.errorCount}"
                }
            }
            matchHistoryList?.adapter =
                ArrayAdapter(this, android.R.layout.simple_list_item_1, currentLines)
        } catch (t: Throwable) {
            RuntimeLogger.log("AnalyticsTheaterActivity: load failed: ${t.message}", "AnalyticsTheaterActivity")
            currentLines = listOf(getString(R.string.splendor_analytics_load_failed))
            matchHistoryList?.adapter =
                ArrayAdapter(this, android.R.layout.simple_list_item_1, currentLines)
        }
    }

    // Save writes the currently displayed lines to a new file
    // analytics_snapshot_<millis>.txt under SplendorStorageRoot,
    // the same pattern as CrashInspector.saveLog.
    private fun saveSnapshot() {
        if (!SplendorStorageRoot.isReady()) {
            RuntimeLogger.log("AnalyticsTheaterActivity: save dropped, storage not ready", "AnalyticsTheaterActivity")
            Toast.makeText(this, R.string.splendor_storage_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val name = "analytics_snapshot_${System.currentTimeMillis()}.txt"
            val file = SplendorStorageRoot.file(name)
            val header = "Splendor Assist analytics snapshot, ${System.currentTimeMillis()}\n" +
                "Source: live adapter-health snapshots (AdapterHealthRegistry.getAllLive()). " +
                "No match-history source exists in this app.\n\n"
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
