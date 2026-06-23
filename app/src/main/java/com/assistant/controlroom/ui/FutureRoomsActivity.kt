package com.assistant.controlroom.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R

class FutureRoomsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_future_rooms)

        val selected = intent.getStringExtra("room_label") ?: "Future Rooms"

        findViewById<TextView>(R.id.tvFutureEmoji).text = "➕"
        findViewById<TextView>(R.id.tvFutureTitle).text = "FUTURE ROOMS"
        findViewById<TextView>(R.id.tvFutureSubtitle).text = "Selected: $selected"
        findViewById<TextView>(R.id.tvFutureList).text =
            (1..30).joinToString("\n") { "Room ${it.toString().padStart(2, '0')}  •  planned" }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }
}
