package com.assistant

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

class UpdateActivity : AppCompatActivity() {

    private var downloadId: Long = -1L
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnDownload: Button
    
    // EXPLICIT REQUIREMENT: Modify this URL to point to your actual GitHub Releases APK direct download link.
    // Standard format: "https://github.com/USERNAME/REPO/releases/latest/download/app-debug.apk"
    private val updateUrl = "https://github.com/USERNAME/REPO/releases/latest/download/app-debug.apk"
    private val fileName = "SplendorAssist_Update.apk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.assistant.overlay.R.layout.activity_update)

        btnDownload = findViewById(com.assistant.overlay.R.id.btnDownloadUpdate)
        val btnSkip = findViewById<Button>(com.assistant.overlay.R.id.btnSkipUpdate)
        progressBar = findViewById(com.assistant.overlay.R.id.updateProgressBar)
        progressText = findViewById(com.assistant.overlay.R.id.progressText)

        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE

        btnDownload.setOnClickListener {
            if (updateUrl.contains("USERNAME")) {
                Toast.makeText(this, "WARN: Replace GitHub URL placeholder in codebase first.", Toast.LENGTH_LONG).show()
            }
            startDownload()
        }

        btnSkip.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    @SuppressLint("Range")
    private fun startDownload() {
        btnDownload.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "0%"

        val request = DownloadManager.Request(Uri.parse(updateUrl))
            .setTitle("Splendor Assist Engine Update")
            .setDescription("Downloading latest OTA build...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = downloadManager.enqueue(request)

        trackProgress(downloadManager)
    }

    @SuppressLint("Range")
    private fun trackProgress(downloadManager: DownloadManager) {
        val handler = Handler(Looper.getMainLooper())
        thread {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false
                        handler.post {
                            progressBar.progress = 100
                            progressText.text = "100%"
                            Toast.makeText(this, "Download Complete. Initializing Installer...", Toast.LENGTH_SHORT).show()
                            installApk()
                            btnDownload.isEnabled = true
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        downloading = false
                        handler.post {
                            Toast.makeText(this, "Download Failed. Check GitHub Release link.", Toast.LENGTH_LONG).show()
                            btnDownload.isEnabled = true
                        }
                    } else if (bytesTotal > 0) {
                        val progress = ((bytesDownloaded * 100L) / bytesTotal).toInt()
                        handler.post {
                            progressBar.progress = progress
                            progressText.text = "$progress%"
                        }
                    }
                }
                cursor?.close()
                try { Thread.sleep(500) } catch (e: InterruptedException) { }
            }
        }
    }

    private fun installApk() {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "APK File corrupted or not found.", Toast.LENGTH_SHORT).show()
        }
    }
}
