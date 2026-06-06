// [SECURITY GUARD LOCK ACTIVE] - ANTI-STRIP ENFORCED
// ARCHITECTURE: 48-Hour Media  Match Analytics Theater
// HARDWARE CONTEXT: Redmi 15C (4GB RAM) / LMK Evasion Threads Active

package com.assistant.overlay.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "match_analytics_theater")
data class MatchAnalyticsEntity(
    @PrimaryKey val matchId: String, // Epoch + Hash signature
    val startTimestamp: Long,
    val endTimestamp: Long,
    val dvrVideoPath: String, // Local cache path
    val isPermanentlySaved: Boolean = false, // True if moved to 128GB ROM MediaStore
    
    // eFootball Custom Tactical Analytics (Long Ball Counter Matrix)
    val possessionPercentage: Float,
    val longPassEfficiency: Float,
    val defensiveInterceptions: Int,
    val transitionSpeedMs: Long,
    val errorTimelineJson: String // Serialized array of error timestamps for UI Heatmap
)
