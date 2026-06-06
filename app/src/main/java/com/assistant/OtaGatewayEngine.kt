// =======================================
// 🛡️ [SECURITY GUARD LOCK ACTIVE]
// MODULE: ISOLATED OTA GATEWAY ENGINE
// THREAD PRIORITY: BACKGROUND (ANTI-STARVATION)
// OVERWRITE PERMISSION: STRICTLY DENIED (APPEND/SED ONLY)
// =======================================
package com.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * [SECURITY GUARD LOCK ACTIVE] - ISOLATED OTA MODULE
 * Thread-Segregated Network Engine & Programmatic UI
 */
object OtaGatewayEngine {
    private val otaExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OtaWorkerThread").apply {
            // STRICT THREAD SEGREGATION: Prevent starvation of Vpn/Accessibility services
            priority = Thread.MIN_PRIORITY 
        }
    }

    fun attachAndExecute(activity: Activity, onSkip: () -> Unit) {
        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F0F13")) // Splendor Assist Premium Dark
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true // Lock UI behind it
            isFocusable = true
        }

        val statusText = TextView(activity).apply {
            text = "INITIALIZING SECURE CONNECTION..."
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(600, 20)
            max = 100
            progress = 0
            visibility = View.GONE
        }

        val actionButton = Button(activity).apply {
            text = "SKIP FOR NOW"
            isEnabled = false
            alpha = 0.5f
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 60, 0, 0) }
            
            setOnClickListener {
                (rootLayout.parent as? ViewGroup)?.removeView(rootLayout)
                onSkip()
            }
        }

        rootLayout.addView(statusText)
        rootLayout.addView(progressBar)
        rootLayout.addView(actionButton)

        // Mount Programmatic UI over existing Activity Root
        activity.runOnUiThread {
            val decorView = activity.window.decorView as ViewGroup
            decorView.addView(rootLayout)
        }

        otaExecutor.execute {
            // System-level enforcement of background priority
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            
            try {
                // SIMULATED ENDPOINT VERIFICATION (Replace with production URL)
                val checkUrl = URL("https://splendor-assist.com/api/v1/ota/latest")
                // TODO: Implement actual JSON check. Simulating an update found for architecture validation.
                val updateExists = true 

                if (!updateExists) {
                    activity.runOnUiThread {
                        statusText.text = "ALL IS UP TO DATE"
                        statusText.setTextColor(Color.GREEN)
                        actionButton.isEnabled = true
                        actionButton.alpha = 1.0f
                    }
                    return@execute
                }

                activity.runOnUiThread {
                    statusText.text = "DOWNLOADING UPDATE [0%]"
                    statusText.setTextColor(Color.parseColor("#FFD700"))
                    progressBar.visibility = View.VISIBLE
                }

                val downloadUrl = URL("https://splendor-assist.com/payload/update.apk") // Dummy Payload
                val connection = downloadUrl.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.connect()

                val fileLength = connection.contentLength
                val apkFile = File(activity.filesDir, "splendor_update.apk")
                
                val input = BufferedInputStream(connection.inputStream)
                val output = FileOutputStream(apkFile)

                // ZERO-ALLOCATION BUFFER (LMK Evasion)
                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int

                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    val progress = (total * 100 / fileLength).toInt()
                    
                    output.write(data, 0, count)
                    
                    activity.runOnUiThread {
                        progressBar.progress = progress
                        statusText.text = "DOWNLOADING UPDATE [$progress%]"
                    }
                }

                output.flush()
                output.close()
                input.close()

                activity.runOnUiThread {
                    statusText.text = "DOWNLOAD COMPLETE. READY TO INSTALL."
                    statusText.setTextColor(Color.GREEN)
                    actionButton.text = "INSTALL & RESTART"
                    actionButton.isEnabled = true
                    actionButton.alpha = 1.0f
                    actionButton.setBackgroundColor(Color.parseColor("#008CBA"))
                    
                    actionButton.setOnClickListener {
                        val uri = FileProvider.getUriForFile(
                            activity,
                            "${activity.packageName}.provider",
                            apkFile
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        activity.startActivity(intent)
                    }
                }

            } catch (e: Exception) {
                activity.runOnUiThread {
                    statusText.text = "CONNECTION FAILED: ${e.message?.uppercase()}"
                    statusText.setTextColor(Color.RED)
                    actionButton.isEnabled = true
                    actionButton.alpha = 1.0f
                }
            }
        }
    }
}
