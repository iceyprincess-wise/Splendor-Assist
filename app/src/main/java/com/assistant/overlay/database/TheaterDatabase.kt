// [SECURITY GUARD LOCK ACTIVE] - ANTI-STRIP ENFORCED
// ARCHITECTURE: 48-Hour Media  Match Analytics Theater
// HARDWARE CONTEXT: Redmi 15C (4GB RAM) / LMK Evasion Threads Active

package com.assistant.overlay.database

import android.content.Context
import android.os.Process
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.Executors

@Database(entities = [MatchAnalyticsEntity::class], version = 1, exportSchema = false)
abstract class TheaterDatabase : RoomDatabase() {
    abstract fun theaterDao(): TheaterDao

    companion object {
        @Volatile
        private var INSTANCE: TheaterDatabase? = null

        // HARDWARE ACCELERATION: Explicit POSIX assignment to prevent UI Thread starvation
        private val lmkEvasionExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread {
                // Drop priority natively so it never interrupts the active 16MB DVR Projection Buffer
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }.apply { name = "Theater_DB_Daemon" }
        }

        fun getDatabase(context: Context): TheaterDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TheaterDatabase::class.java,
                    "splendor_assist_theater.db"
                )
                .setQueryExecutor(lmkEvasionExecutor)
                .setTransactionExecutor(lmkEvasionExecutor)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
