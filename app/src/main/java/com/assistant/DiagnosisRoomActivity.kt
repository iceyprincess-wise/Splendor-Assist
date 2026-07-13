package com.assistant

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.assistant.diagnostic.RuntimeLogger

class DiagnosisRoomActivity : AppCompatActivity() {
    private val engines = listOf(
        "MagneticFeetEngine",
        "GameplayDecisionEngine",
        "CrossingLaneAnalysisEngine",
        "SmartAssistMetrics",
        "SmartAssistControlRoomActivity",
        "RuntimeLogger"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeLogger.reconcileExpired()
        setContentView(buildRoom())
    }

    private fun buildRoom(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        root.addView(TextView(this).apply {
            text = "Hot Wired Diagnosis Room"
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Tap an engine to open its full diagnosis page."
            textSize = 13f
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        engines.forEach { engine ->
            list.addView(Button(this).apply {
                text = engine
                setOnClickListener {
                    startActivity(
                        Intent(this@DiagnosisRoomActivity, DiagnosisDetailActivity::class.java)
                            .putExtra(DiagnosisDetailActivity.EXTRA_ENGINE, engine)
                    )
                }
            })
        }

        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })

        return root
    }
}
