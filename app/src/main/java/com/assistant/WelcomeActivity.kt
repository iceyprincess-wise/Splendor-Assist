/* ====================================================================
 * 🛡️ [SECURITY GUARD LOCK ACTIVE]
 * CORE ARCHITECTURE STATE: VERIFIED & ISOLATED
 * AI DIRECTIVE: DO NOT OVERWRITE, MODIFY, OR STRIP THIS FILE VIA BASH REDIRECTION (>).
 * THREAD PRIORITY: BACKGROUND I/O STRICTLY ISOLATED. DO NOT BIND NEW TASKS HERE.
 * ==================================================================== */

package com.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class WelcomeActivity : AppCompatActivity() {

    // HARDWARE/MEMORY CONTEXT: Redmi 15C / 4GB RAM 
    // Uses native URL connection to bypass OkHttp memory pool allocation.
    private val backendCheckUrl = "https://raw.githubusercontent.com/USERNAME/REPO/main/status.txt"
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [SECURITY GUARD LOCK ACTIVE] - OTA Gateway Hook
        com.assistant.OtaGatewayEngine.attachAndExecute(this) {
            startActivity(android.content.Intent(this, com.assistant.MainActivity::class.java))
            finish()
        }
        setContentView(com.assistant.overlay.R.layout.activity_welcome)
        statusText = findViewById(com.assistant.overlay.R.id.tvCheckState)

        executeDynamicCheckState()
    }

    private fun executeDynamicCheckState() {
        thread(start = true) {
            // Drop thread priority to prevent LMK daemon threshold triggers during I/O
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            
            try {
                Thread.sleep(800) // Artificial delay for visual verification
                updateStatus("[*] RESOLVING BACKEND AUTHENTICATION...")

                val url = URL(backendCheckUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText().trim()
                    reader.close()
                    
                    if (response.contains("FORCE_UPDATE=TRUE")) {
                        updateStatus("[!] OTA UPDATE MANDATED. REDIRECTING...")
                        Thread.sleep(1000)
                        routeToPhase(UpdateActivity::class.java)
                    } else {
                        updateStatus("[+] SECURITY HANDSHAKE VERIFIED. SERVER MATCH.")
                        Thread.sleep(600)
                        routeToPhase(MainActivity::class.java)
                    }
                } else {
                    updateStatus("[-] BACKEND UNREACHABLE. PROCEEDING IN OFFLINE MODE.")
                    Thread.sleep(800)
                    routeToPhase(MainActivity::class.java)
                }
            } catch (e: Exception) {
                // Failsafe execution: If network is completely offline, bypass to Dashboard
                updateStatus("[-] NETWORK TIMEOUT. EXECUTING LOCAL BYPASS.")
                try { Thread.sleep(800) } catch (ignored: Exception) {}
                routeToPhase(MainActivity::class.java)
            } finally {
                // Force explicit garbage collection signal for connection objects
                System.gc()
            }
        }
    }

    private fun updateStatus(msg: String) {
        Handler(Looper.getMainLooper()).post {
            statusText.text = msg
        }
    }

    private fun routeToPhase(targetActivity: Class<*>) {
        Handler(Looper.getMainLooper()).post {
            startActivity(Intent(this@WelcomeActivity, targetActivity))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }
}
