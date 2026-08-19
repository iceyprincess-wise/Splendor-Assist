package com.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.assistant.audit.SelfAuditRegistry
import com.assistant.compliance.ComplianceState
import com.assistant.diagnostic.RuntimeMetricsRegistry
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.survival.ProcessSurvivalRegistry
import com.assistant.survival.ResourceBudgetRegistry
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

object DashboardInjector {

    private const val DASHBOARD_TAG = "splendor_dashboard_overlay"

    private var activeContainer: LinearLayout? = null
    private var activeHandler: Handler? = null
    private var activeRunnable: DashboardRefreshRunnable? = null
    
    private val bgExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Splendor-DashboardPoll").apply { priority = Thread.MIN_PRIORITY }
    }

    fun attach(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)

        // Cleanup previous state
        detach()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(40, 40, 40, 120)
            tag = DASHBOARD_TAG
        }

        val title = TextView(activity).apply {
            text = "SPLENDOR ASSIST PRO"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        val runtime = TextView(activity).apply {
            text = "Runtime Nodes : ${AdapterHealthRegistry.getAll().size}"
            textSize = 14f
            setTextColor(Color.GREEN)
        }

        val metrics = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.YELLOW)
        }

        val status = TextView(activity).apply {
            text = ComplianceState.summary(activity)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.CYAN)
        }

        val launch = Button(activity).apply {
            text = "ACTIVATE ALL ADAPTERS"
            setOnClickListener {
                // Use applicationContext to prevent leaking the Activity in the engine
                IgnitionEngine.ignite(activity.applicationContext)
                runtime.text = "Runtime Nodes : ${AdapterHealthRegistry.getAll().size}"
                status.text = ComplianceState.summary(activity)
            }
        }

        val adapterStatus = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
        }

        container.addView(title)
        container.addView(runtime)
        container.addView(metrics)
        container.addView(adapterStatus)
        container.addView(status)
        container.addView(launch)

        root.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        activeContainer = container
        
        val handler = Handler(Looper.getMainLooper())
        activeHandler = handler
        
        val runnable = DashboardRefreshRunnable(
            WeakReference(activity), 
            WeakReference(metrics), 
            WeakReference(status), 
            handler
        )
        activeRunnable = runnable
        handler.post(runnable)
    }

    fun detach() {
        activeHandler?.removeCallbacksAndMessages(null)
        activeHandler = null
        activeRunnable = null
        
        activeContainer?.let { previous ->
            try {
                (previous.parent as? ViewGroup)?.removeView(previous)
            } catch (_: Exception) {}
        }
        activeContainer = null
    }

    private class DashboardRefreshRunnable(
        private val activityRef: WeakReference<Activity>,
        private val metricsRef: WeakReference<TextView>,
        private val statusRef: WeakReference<TextView>,
        private val handler: Handler
    ) : Runnable {
        override fun run() {
            val activity = activityRef.get()
            val metricsView = metricsRef.get()
            val statusView = statusRef.get()

            if (activity == null || metricsView == null || statusView == null) {
                // Activity or views are gone, stop polling to prevent leaks
                return 
            }

            // Offload heavy string building to background thread
            bgExecutor.execute {
                val metricsText = buildString {
                    append(RuntimeMetricsRegistry.snapshot()).append("\n\n")
                    append(ProcessSurvivalRegistry.snapshot()).append("\n\n")
                    append(ResourceBudgetRegistry.snapshot()).append("\n\n")
                    append(SelfAuditRegistry.snapshot())
                }
                
                val statusText = ComplianceState.summary(activity)

                // Post only the final text to Main Thread to eliminate GC pressure
                handler.post {
                    if (metricsView.isAttachedToWindow) {
                        metricsView.text = metricsText
                        statusView.text = statusText
                        handler.postDelayed(this@DashboardRefreshRunnable, 1000L)
                    }
                }
            }
        }
    }
}
