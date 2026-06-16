package com.assistant

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R

class SplashActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var percent: TextView
    private lateinit var status: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#08111F"))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.splendor_assist_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams =
                LinearLayout.LayoutParams(
                    420,
                    420
                )
        }

        val title = TextView(this).apply {
            text = "Splendor Assist"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "Resources loaded"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        percent = TextView(this).apply {
            text = "0%"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            layoutParams =
                LinearLayout.LayoutParams(
                    900,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
        }

        root.addView(logo)
        root.addView(title)
        root.addView(status)
        root.addView(percent)
        root.addView(progress)

        setContentView(root)

        runStartup()
    }

    private fun runStartup() {

        val handler = Handler(Looper.getMainLooper())

        for (i in 0..100) {

            handler.postDelayed({

                progress.progress = i
                percent.text = "$i%"

                status.text =
                    when {
                        i <= 20 -> "Resources loaded"
                        i <= 40 -> "Engine checks"
                        i <= 60 -> "Adapter registry checks"
                        i <= 80 -> "Analytics checks"
                        else -> "OCR initialization"
                    }

                if (i == 100) {
                    startActivity(
                        Intent(
                            this,
                            WelcomeActivity::class.java
                        )
                    )
                    finish()
                }

            }, (i * 35).toLong())
        }
    }
}
