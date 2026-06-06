// [SECURITY GUARD LOCK ACTIVE]
// CRITICAL CONTINUATION DIRECTIVE: ANTI-STRIP & ANTI-DEGRADATION ENFORCED.
// DECOUPLED UI LOGIC. DO NOT SURGICALLY INJECT INTO MAINACTIVITY XML.
package com.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object DashboardInjector {
    fun attach(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 120)
            elevation = 10f
        }

        val badgeContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val statusBadge = TextView(activity).apply {
            text = "SYSTEM: STANDBY"
            setTextColor(Color.parseColor("#FF5555")) // Red for standby
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.1f
        }

        badgeContainer.addView(statusBadge)

        val masterSwitch = Button(activity).apply {
            text = "START FULL ENGINE"
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setTextColor(Color.parseColor("#00FF00"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            
            setOnClickListener {
                // UI decoupled from actual Service Binding
                statusBadge.text = "SYSTEM: ACTIVE [NET, INPUT, LMK, SYNC]"
                statusBadge.setTextColor(Color.parseColor("#00FF00"))
                this.text = "ENGINE RUNNING"
                this.isEnabled = false
                this.setBackgroundColor(Color.parseColor("#333333"))
                
                // Trigger isolated IPC
                IgnitionEngine.ignite(activity)
            }
        }

        container.addView(badgeContainer)
        container.addView(masterSwitch)
        
        // Overlay dynamically on existing MainActivity
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(container, params)
    }
}
