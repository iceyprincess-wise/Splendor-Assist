// [SECURITY GUARD LOCK ACTIVE] - ANTI-STRIP ENFORCED
// ARCHITECTURE: 48-Hour Media  Match Analytics Theater
// HARDWARE CONTEXT: Redmi 15C (4GB RAM) / LMK Evasion Threads Active

package com.assistant.overlay.database

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File
import java.util.concurrent.Executors

class TheaterCleanupReceiver : BroadcastReceiver() {
    
    // Explicit low-priority executor to evade HyperOS lmkd during I/O operations
    private val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread {
            // Drops priority natively to protect the locked 16MB DVR Projection Buffer
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }.apply { name = "Theater_Cleanup_Daemon" }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        
        cleanupExecutor.execute {
            try {
                val db = TheaterDatabase.getDatabase(context)
                val dao = db.theaterDao()
                
                // 48 hours in milliseconds = 172,800,000L
                val expirationEpoch = System.currentTimeMillis() - 172800000L
                val expiredMatches = dao.getExpiredMatchesForDeletion(expirationEpoch)
                
                for (match in expiredMatches) {
                    val videoFile = File(match.dvrVideoPath)
                    
                    // Clear app cache explicitly before the 48-hour deadline if not saved to the 128GB ROM
                    if (videoFile.exists() && !match.isPermanentlySaved) {
                        videoFile.delete() 
                    }
                    dao.dropMatchRecord(match.matchId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
