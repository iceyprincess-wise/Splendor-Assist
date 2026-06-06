// [SECURITY GUARD LOCK ACTIVE] - ANTI-STRIP ENFORCED
// ARCHITECTURE: 48-Hour Media  Match Analytics Theater
// HARDWARE CONTEXT: Redmi 15C (4GB RAM) / LMK Evasion Threads Active

package com.assistant.overlay.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.assistant.overlay.R
import com.assistant.overlay.database.TheaterDatabase
import java.io.File
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.assistant.overlay.ui.adapter.MatchHistoryAdapter
import com.assistant.overlay.storage.MediaStoreStorageEngine

class AnalyticsTheaterActivity : AppCompatActivity() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var btnSaveRom: Button
    private lateinit var tvCountdown: TextView
    private lateinit var rvMatchHistory: RecyclerView
    private lateinit var matchAdapter: MatchHistoryAdapter
    private var activeMatchId: String? = null
    private var activeMatchSourcePath: String? = null

    // Isolate UI queries to background to prevent main thread frame drops
    private val dbQueryExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics_theater)

        playerView = findViewById(R.id.dvr_player_view)
        btnSaveRom = findViewById(R.id.btn_save_rom)
        tvCountdown = findViewById(R.id.tv_ttl_countdown)

        rvMatchHistory = findViewById(R.id.rv_match_history)
        rvMatchHistory.layoutManager = LinearLayoutManager(this)
        matchAdapter = MatchHistoryAdapter { selectedMatch ->
            activeMatchId = selectedMatch.matchId
            activeMatchSourcePath = selectedMatch.dvrVideoPath
            loadVideoPayload(selectedMatch.dvrVideoPath)
            calculateAndBindTTL(selectedMatch.endTimestamp)
        }
        rvMatchHistory.adapter = matchAdapter

        btnSaveRom.setOnClickListener {
            val mId = activeMatchId
            val mPath = activeMatchSourcePath
            if (mId != null && mPath != null) {
                btnSaveRom.isEnabled = false
                btnSaveRom.text = "SAVING..."
                MediaStoreStorageEngine.saveToRom(this@AnalyticsTheaterActivity, mId, mPath) {
                    mainHandler.post {
                        Toast.makeText(this@AnalyticsTheaterActivity, "Saved to 128GB ROM", Toast.LENGTH_SHORT).show()
                        btnSaveRom.text = "SAVE TO 128GB ROM"
                        btnSaveRom.isEnabled = true
                        loadMatchHistory()
                    }
                }
            }
        }
        initializePlayer()
        loadMatchHistory()
    }

    private fun initializePlayer() {
        // Initializes player safely; memory is allocated only when a match is selected
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
    }

    private fun loadMatchHistory() {
        dbQueryExecutor.execute {
            val db = TheaterDatabase.getDatabase(this)
            val activeMatches = db.theaterDao().getActiveTheaterMatches()

            mainHandler.post {
                    matchAdapter.submitList(activeMatches)
                // If activeMatches is not empty, load the most recent into the player
                if (activeMatches.isNotEmpty()) {
                    val latestMatch = activeMatches[0]
                    activeMatchId = latestMatch.matchId
                    activeMatchSourcePath = latestMatch.dvrVideoPath
                    loadVideoPayload(latestMatch.dvrVideoPath)
                    calculateAndBindTTL(latestMatch.endTimestamp)
                }
            }
        }
    }

    private fun loadVideoPayload(filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            val mediaItem = MediaItem.fromUri(file.toURI().toString())
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            // Do NOT auto-play to conserve RAM until explicit user interaction
        }
    }

    private fun calculateAndBindTTL(endTimestamp: Long) {
        // 48 hours = 172,800,000 ms
        val expirationTime = endTimestamp + 172800000L
        val timeRemaining = expirationTime - System.currentTimeMillis()
        
        if (timeRemaining > 0) {
            val hours = (timeRemaining / (1000 * 60 * 60)) % 24
            val minutes = (timeRemaining / (1000 * 60)) % 60
            tvCountdown.text = String.format("TTL: %02dh %02dm", hours, minutes)
        } else {
            tvCountdown.text = "EXPIRED (Awaiting Daemon)"
            tvCountdown.setTextColor(android.graphics.Color.DKGRAY)
        }
    }

    override fun onStop() {
        super.onStop()
        // STRICT LMK EVASION: Release player instantly when backgrounded
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // FREE RAM COMPLETELY
        exoPlayer?.release()
        exoPlayer = null
        dbQueryExecutor.shutdown()
    }
}
