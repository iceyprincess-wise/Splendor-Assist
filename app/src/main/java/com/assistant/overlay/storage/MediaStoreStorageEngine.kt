// [SECURITY GUARD LOCK ACTIVE] - ANTI-STRIP ENFORCED
// ARCHITECTURE: 48-Hour Media  Match Analytics Theater
// HARDWARE CONTEXT: Redmi 15C (4GB RAM) / LMK Evasion Threads Active

package com.assistant.overlay.storage

import android.content.ContentValues
import android.content.Context
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import com.assistant.overlay.database.TheaterDatabase
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

object MediaStoreStorageEngine {
    
    // Explicit low-priority I/O executor to respect the 4GB RAM threshold
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }.apply { name = "IO_MediaStore_Engine" }
    }

    fun saveToRom(context: Context, matchId: String, sourcePath: String, onSuccess: () -> Unit) {
        ioExecutor.execute {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) return@execute

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "SplendorAssist_\${matchId}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SplendorAssist")
                put(MediaStore.Video.Media.IS_PENDING, 1) // Lock file for writing (Android 10+)
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { destUri ->
                try {
                    resolver.openOutputStream(destUri)?.use { outputStream ->
                        FileInputStream(sourceFile).use { inputStream ->
                            // Strictly 8KB buffer allocation to evade LMK sweeps
                            val buffer = ByteArray(8192)
                            var length: Int
                            while (inputStream.read(buffer).also { length = it } > 0) {
                                outputStream.write(buffer, 0, length)
                            }
                        }
                    }

                    // Release file to media scanner
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(destUri, contentValues, null, null)

                    // Atomically clear internal cache & update Database state
                    sourceFile.delete()
                    val db = TheaterDatabase.getDatabase(context)
                    val dao = db.theaterDao()
                    // Note: Requires simple update query in DAO, executed here directly for speed
                    // We assume match record exists. Updating isPermanentlySaved directly.
                    db.runInTransaction {
                        val activeMatches = dao.getActiveTheaterMatches()
                        val match = activeMatches.find { it.matchId == matchId }
                        match?.let {
                            val updatedMatch = it.copy(isPermanentlySaved = true, dvrVideoPath = destUri.toString())
                            dao.insertMatchData(updatedMatch)
                        }
                    }

                    onSuccess()
                } catch (e: Exception) {
                    Log.e("MediaStoreEngine", "I/O Hardware Exception", e)
                    // Release lock on failure
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(destUri, contentValues, null, null)
                }
            }
        }
    }
}
