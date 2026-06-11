package com.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class LogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val btnCopy = Button(this).apply { text = "COPY ANATOMICAL REPORT" }
        val txtLogs = TextView(this).apply { 
            textSize = 12f
            setTextIsSelectable(true)
        }
        
        // Read Crash Logs
        val crashFile = File(getExternalFilesDir(null), "crash_log.txt")
        val crashLogs = if (crashFile.exists()) crashFile.readText() else "SYSTEM STABLE: NO CRASHES DETECTED.\n"
        
        // Read Runtime Diagnostics
        val runtimeFile = File(filesDir, "runtime_diagnostic.txt")
        val runtimeLogs = if (runtimeFile.exists()) runtimeFile.readText() else "NO RUNTIME DIAGNOSTICS FOUND.\n"
        
        val combinedLogs = "=== RUNTIME DIAGNOSTICS ===\n$runtimeLogs\n\n=== CRASH LOGS ===\n$crashLogs"
        
        txtLogs.text = combinedLogs
        
        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Engine Logs", combinedLogs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs Copied to Clipboard", Toast.LENGTH_SHORT).show()
        }
        
        layout.addView(btnCopy)
        layout.addView(ScrollView(this).apply { addView(txtLogs) })
        setContentView(layout)
    }
}
