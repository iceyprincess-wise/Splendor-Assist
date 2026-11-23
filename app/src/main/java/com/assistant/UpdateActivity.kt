package com.assistant

import android.app.AlarmManager
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * In-app update page (updater Pieces 3 + 4) — inflates the orphaned
 * activity_update layout (File No 96, no60 premium re-touch), tuned
 * to the eFootball maintenance style.
 *
 * This page appears ONLY when an update is CONFIRMED available — the
 * splash hook (SplashActivity.maybeShowUpdatePage) enforces that; this
 * activity is never started otherwise. It shows the available version
 * (and the release notes, when the checker fetched them) with a
 * DOWNLOAD & INSTALL button and a SKIP FOR NOW button.
 *
 * SKIP FOR NOW does NOT enter the app — it exits (finishAffinity). If
 * a download is in progress, SKIP cancels it first.
 *
 * DOWNLOAD & INSTALL (Piece 4): downloads the release APK with
 * DownloadManager (progress into updateProgressBar / progressText),
 * then installs it via FileProvider. The install-unknown-apps flow
 * (REQUEST_INSTALL_PACKAGES) is checked first: if the app cannot
 * request installs, the user is sent to the "Allow from this source"
 * settings page and the download resumes when they return.
 *
 * Download failure stays on screen with a retry (the DOWNLOAD button
 * is re-enabled); it does NOT exit and does NOT enter the app.
 * Fail-open applies to the CHECK only (Piece 3) — a download failure
 * is not a check failure.
 *
 * After the install intent fires, the app schedules its own restart
 * (AlarmManager, ~5s, relaunching SplashActivity) and closes, so the
 * update takes effect on its own. The installed update is recorded
 * in SharedPreferences (version + time) so Settings can show the
 * update history (the Settings page itself is a later piece).
 */
class UpdateActivity : AppCompatActivity() {

    companion object {
        /** The latest release's versionCode (Long). */
        const val EXTRA_LATEST_VERSION_CODE = "extra_latest_version_code"

        /** The latest release's notes (String?), may be absent. */
        const val EXTRA_RELEASE_NOTES = "extra_release_notes"

        /** The APK download URL (String), from UpdateChecker.Result. */
        const val EXTRA_APK_DOWNLOAD_URL = "extra_apk_download_url"

        private const val PREFS_NAME = "splendor_update_history"
        private const val KEY_HISTORY = "update_history"
        private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
        private const val KEY_PENDING_DOWNLOAD_VERSION = "pending_download_version"
        private const val HISTORY_MAX_ENTRIES = 20
        private const val APK_FILE_NAME = "app-release.apk"
        private const val PROGRESS_POLL_MS = 500L
        private const val RESTART_DELAY_MS = 5000L

        /**
         * The recorded update history, newest first, as
         * (versionCode, installedAtMillis) pairs. Read by the Settings
         * page (later piece). Empty when no update has been installed
         * through the in-app updater yet.
         */
        fun readHistory(context: Context): List<Pair<Long, Long>> {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_HISTORY, null) ?: return emptyList()
            return raw.split(";").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size != 2) null
                else {
                    val version = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val at = parts[1].toLongOrNull() ?: return@mapNotNull null
                    version to at
                }
            }
        }
    }

    private var downloadId: Long = -1L
    private var latestVersionCode: Long = -1L
    private var apkDownloadUrl: String? = null
    private var pendingUnknownSources: Boolean = false
    private var downloadInProgress: Boolean = false
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = Runnable { pollDownload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge (targetSdk 36 enforces it) — draw behind the
        // system bars; the insets are applied to the content frame so
        // the text and buttons never sit under a system bar.
        enableEdgeToEdge()
        setContentView(com.assistant.overlay.R.layout.activity_update)
        val content = findViewById<android.view.View>(android.R.id.content)
        if (content != null) {
            ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        // Show the available version (and the release notes) in the
        // subtitle. The notes are capped so a long changelog cannot
        // push the buttons off the page.
        latestVersionCode = intent.getLongExtra(EXTRA_LATEST_VERSION_CODE, -1L)
        apkDownloadUrl = intent.getStringExtra(EXTRA_APK_DOWNLOAD_URL)
        val releaseNotes = intent.getStringExtra(EXTRA_RELEASE_NOTES)
        val subtitle = findViewById<TextView>(com.assistant.overlay.R.id.updateSubtitle)
        if (latestVersionCode > 0) {
            val versionLine = "Version $latestVersionCode is available."
            val notes = releaseNotes?.take(600)?.trimEnd()
            subtitle?.text = if (!notes.isNullOrBlank()) "$versionLine\n\n$notes" else versionLine
        }

        // DOWNLOAD & INSTALL — Piece 4: DownloadManager + install.
        findViewById<android.view.View>(com.assistant.overlay.R.id.btnDownloadUpdate)
            ?.setOnClickListener { startUpdateDownload() }

        // SKIP FOR NOW — does NOT enter the app; it exits. Cancels an
        // in-progress download first.
        findViewById<android.view.View>(com.assistant.overlay.R.id.btnSkipUpdate)
            ?.setOnClickListener {
                cancelDownloadIfRunning()
                finishAffinity()
            }

        // Resume a download that was running when this activity was
        // recreated (rotation / process death). The DownloadManager
        // keeps the download going; we just re-attach the polling.
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingId = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, -1L)
        val pendingVersion = prefs.getLong(KEY_PENDING_DOWNLOAD_VERSION, -1L)
        if (pendingId >= 0 && pendingVersion == latestVersionCode) {
            downloadId = pendingId
            downloadInProgress = true
            setDownloadingUi(true)
            pollHandler.post(pollRunnable)
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the "Allow from this source" settings page:
        // if the permission is now granted, proceed with the download.
        if (pendingUnknownSources) {
            pendingUnknownSources = false
            if (packageManager.canRequestPackageInstalls()) {
                beginDownload()
            } else {
                setProgressText("Install permission not granted. Tap DOWNLOAD & INSTALL to try again.")
                setDownloadButtonEnabled(true)
            }
        }
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    /**
     * DOWNLOAD & INSTALL entry point. Checks the install-unknown-apps
     * permission first (Ed's Piece 4 decision), then starts the
     * DownloadManager download.
     */
    private fun startUpdateDownload() {
        if (downloadInProgress) return
        if (apkDownloadUrl.isNullOrBlank()) {
            setProgressText("Download link not available.")
            return
        }
        // Install-unknown-apps flow: the app must be allowed to install
        // packages from this source before the install intent can run.
        if (!packageManager.canRequestPackageInstalls()) {
            pendingUnknownSources = true
            setProgressText("Enable 'Allow from this source', then return here.")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
            return
        }
        beginDownload()
    }

    /** Enqueues the APK download and starts progress polling. */
    private fun beginDownload() {
        val url = apkDownloadUrl ?: return
        val request = DownloadManager.Request(url.toUri()).apply {
            setTitle("Splendor Assist update")
            setDescription("Downloading version $latestVersionCode")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalFilesDir(
                this@UpdateActivity, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME
            )
            setMimeType("application/vnd.android.package-archive")
        }
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
        downloadInProgress = true
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
            putLong(KEY_PENDING_DOWNLOAD_VERSION, latestVersionCode)
        }
        setDownloadingUi(true)
        setProgressText("Downloading… 0%")
        pollHandler.post(pollRunnable)
    }

    /** Polls the DownloadManager for progress / completion / failure. */
    private fun pollDownload() {
        if (!downloadInProgress || downloadId < 0) return
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        if (cursor == null) {
            onDownloadFailed()
            return
        }
        cursor.use {
            if (!it.moveToFirst()) {
                onDownloadFailed()
                return
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    downloadInProgress = false
                    clearPendingDownload()
                    installDownloadedApk()
                }
                DownloadManager.STATUS_FAILED -> onDownloadFailed()
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_PENDING -> {
                    val downloaded = it.getLong(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val total = it.getLong(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    if (total > 0) {
                        val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                        setDownloadProgress(percent)
                        setProgressText("Downloading… $percent%")
                    } else {
                        setProgressText("Downloading…")
                    }
                    pollHandler.postDelayed(pollRunnable, PROGRESS_POLL_MS)
                }
                else -> pollHandler.postDelayed(pollRunnable, PROGRESS_POLL_MS)
            }
        }
    }

    /** The download failed: stay on screen, show the failure, allow retry. */
    private fun onDownloadFailed() {
        downloadInProgress = false
        clearPendingDownload()
        pollHandler.removeCallbacks(pollRunnable)
        setDownloadingUi(false)
        setProgressText("Download failed. Tap DOWNLOAD & INSTALL to retry.")
        setDownloadButtonEnabled(true)
    }

    /** Cancels a running download (SKIP) and clears the pending state. */
    private fun cancelDownloadIfRunning() {
        if (downloadInProgress && downloadId >= 0) {
            runCatching {
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
            }
        }
        downloadInProgress = false
        clearPendingDownload()
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun clearPendingDownload() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_PENDING_DOWNLOAD_ID)
            remove(KEY_PENDING_DOWNLOAD_VERSION)
        }
    }

    /**
     * Installs the downloaded APK via FileProvider, records the update
     * in history, schedules the auto-restart, and closes this page so
     * the installer UI is in front.
     */
    private fun installDownloadedApk() {
        val apkFile = File(
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME
        )
        if (!apkFile.exists()) {
            onDownloadFailed()
            return
        }
        val uri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        recordUpdateHistory(latestVersionCode)
        startActivity(installIntent)
        scheduleRestart()
        finishAffinity()
    }

    /**
     * Auto-restart (Ed's Piece 4 requirement): after the install, the
     * app restarts on its own so the update takes effect. An alarm
     * relaunches the splash (~5s — enough time to confirm the install
     * in the package installer UI). If the user is slower than that,
     * the alarm relaunches the old version, which re-prompts for the
     * update via the splash check — not broken, just re-prompted.
     * setAndAllowWhileIdle fires even in Doze and needs no exact-alarm
     * permission (setExact would need SCHEDULE_EXACT_ALARM granted).
     */
    private fun scheduleRestart() {
        val intent = Intent(this, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            alarm.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + RESTART_DELAY_MS,
                pending
            )
        }
    }

    /**
     * Records an installed update (version + time) in SharedPreferences
     * so Settings can show the update history. Recorded when the
     * install intent fires (the package installer confirms the rest).
     * Newest first, capped so the list cannot grow unbounded.
     */
    private fun recordUpdateHistory(version: Long) {
        if (version <= 0) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = readHistory(this).toMutableList()
        val entry = version to System.currentTimeMillis()
        existing.removeAll { it.first == version }
        existing.add(0, entry)
        val stored = existing.take(HISTORY_MAX_ENTRIES)
            .joinToString(";") { "${it.first}|${it.second}" }
        prefs.edit { putString(KEY_HISTORY, stored) }
    }

    private fun setDownloadingUi(downloading: Boolean) {
        val bar = findViewById<ProgressBar>(com.assistant.overlay.R.id.updateProgressBar)
        val text = findViewById<TextView>(com.assistant.overlay.R.id.progressText)
        bar?.visibility = if (downloading) View.VISIBLE else View.GONE
        text?.visibility = if (downloading) View.VISIBLE else View.VISIBLE
        if (downloading) bar?.progress = 0
        setDownloadButtonEnabled(!downloading)
    }

    private fun setDownloadProgress(percent: Int) {
        findViewById<ProgressBar>(com.assistant.overlay.R.id.updateProgressBar)
            ?.progress = percent
    }

    private fun setProgressText(text: String) {
        val view = findViewById<TextView>(com.assistant.overlay.R.id.progressText)
        view?.visibility = View.VISIBLE
        view?.text = text
    }

    private fun setDownloadButtonEnabled(enabled: Boolean) {
        findViewById<android.view.View>(com.assistant.overlay.R.id.btnDownloadUpdate)
            ?.isEnabled = enabled
    }
}
