package com.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.assistant.diagnostic.registry.AdapterHealthRegistry

object DashboardInjector {

    fun attach(activity: Activity) {

        val root =
            activity.findViewById<ViewGroup>(
                android.R.id.content
            )

        val container =
            LinearLayout(activity).apply {

                orientation = LinearLayout.VERTICAL

                gravity =
                    Gravity.BOTTOM or
                    Gravity.CENTER_HORIZONTAL

                setPadding(
                    40,
                    40,
                    40,
                    120
                )
            }

        val title =
            TextView(activity).apply {

                text =
                    "SPLENDOR ASSIST PRO"

                textSize = 22f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    Color.WHITE
                )
            }

        val runtime =
            TextView(activity).apply {

                text =
                    "Runtime Nodes : ${
                        AdapterHealthRegistry.getAll().size
                    }"

                textSize = 14f

                setTextColor(
                    Color.GREEN
                )
            }

        val status =
            TextView(activity).apply {

                text =
                    "ENGINE READY"

                textSize = 14f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    Color.CYAN
                )
            }

        val launch =
            Button(activity).apply {

                text =
                    "ACTIVATE ALL ADAPTERS"

                setOnClickListener {

                    IgnitionEngine.ignite(activity)

                    status.text =
                        "ENGINE ACTIVE"

                    runtime.text =
                        "Runtime Nodes : ${
                            AdapterHealthRegistry.getAll().size
                        }"
                }
            }

        container.addView(title)
        container.addView(runtime)
        container.addView(status)
        container.addView(launch)

        root.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }
}
