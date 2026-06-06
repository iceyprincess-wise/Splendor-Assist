// [SECURITY GUARD LOCK ACTIVE] - ANTI-STRIP ENFORCED
// ARCHITECTURE: 48-Hour Media  Match Analytics Theater
// HARDWARE CONTEXT: Redmi 15C (4GB RAM) / LMK Evasion Threads Active

package com.assistant.overlay.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TheaterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMatchData(match: MatchAnalyticsEntity)

    // Retrieve active 48-Hour Theater matches
    @Query("SELECT * FROM match_analytics_theater WHERE isPermanentlySaved = 0 ORDER BY endTimestamp DESC")
    fun getActiveTheaterMatches(): List<MatchAnalyticsEntity>

    // Auto-Delete Daemon Query: Fetch matches older than exactly 48 hours
    @Query("SELECT * FROM match_analytics_theater WHERE endTimestamp < :expirationEpoch AND isPermanentlySaved = 0")
    fun getExpiredMatchesForDeletion(expirationEpoch: Long): List<MatchAnalyticsEntity>

    @Query("DELETE FROM match_analytics_theater WHERE matchId = :matchId")
    fun dropMatchRecord(matchId: String)
}
