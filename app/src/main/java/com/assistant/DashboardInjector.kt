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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * DashboardInjector
 *
 * P1 FIX: bgExecutor lifecycle.
 * Was a singleton val on the object -- never shut down after detach().
 * Thread "Splendor-DashboardPoll" persisted permanently, consuming a thread
 * slot and Binder IPC budget on the 4GB Redmi 15C.
 * FIX: bgExecutor is instance-scoped. Created on attach(), shut down on detach().
 *
 * P1 FIX: Fleet lifecycle display.
 * Dashboard now shows COLD/PARTIAL/WARMING/READY/DEGRADED state directly.
 * Previously showed only raw adapter count -- misread as health proof.
 * VISIBLE EVIDENCE: color-coded fleet state line, live every 1 second.
 */
object DashboardInjector {

    private const val DASHBOARD_TAG = "splendor_dashboard_overlay"

    private var activeContainer : LinearLayout?             = null
    private var activeHandler   : Handler?                  = null
    private var activeRunnable  : DashboardRefreshRunnable? = null

    // P1 FIX: instance-scoped -- was val on object (permanent thread leak).
    private var bgExecutor: ExecutorService? = null

    fun attach(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        detach()

        // P1 FIX: fresh executor per attach -- previous shut down in detach().
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "Splendor-DashboardPoll").apply { priority = Thread.MIN_PRIORITY }
        }
        bgExecutor = executor

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

        // P1 FIX: Fleet lifecycle display (COLD/PARTIAL/WARMING/READY/DEGRADED).
        val fleetStateView = TextView(activity).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        updateFleetStateView(fleetStateView)

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
                val success = IgnitionEngine.ignite(activity.applicationContext)
                runtime.text = if (success)
                    "Fleet ignition scheduled -- verifying in 9s..."
                else
                    "Ignition Blocked"
                status.text = ComplianceState.summary(activity)
                updateFleetStateView(fleetStateView)
            }
        }

        val adapterStatus = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
        }

        container.addView(title)
        container.addView(fleetStateView)
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
            WeakReference(fleetStateView),
            WeakReference(metrics),
            WeakReference(status),
            handler,
            executor
        )
        activeRunnable = runnable
        handler.post(runnable)
    }

    fun detach() {
        activeHandler?.removeCallbacksAndMessages(null)
        activeHandler  = null
        activeRunnable = null

        // P1 FIX: shut down executor -- terminates the background thread cleanly.
        bgExecutor?.shutdown()
        bgExecutor = null

        activeContainer?.let { previous ->
            try { (previous.parent as? ViewGroup)?.removeView(previous) } catch (_: Exception) {}
        }
        activeContainer = null
    }

    private fun updateFleetStateView(view: TextView) {
        val state = BoosterIgnition.currentState()
        val snap  = BoosterIgnition.fleetSnapshot()
        val (text, color) = when (state) {
            FleetLifecycleState.COLD     -> "Fleet: COLD -- not started"   to Color.GRAY
            FleetLifecycleState.PARTIAL  -> "Fleet: LAUNCHING..."           to Color.YELLOW
            FleetLifecycleState.WARMING  -> "Fleet: WARMING -- $snap"      to Color.parseColor("#FF8C00")
            FleetLifecycleState.READY    -> "Fleet: READY -- $snap"        to Color.GREEN
            FleetLifecycleState.DEGRADED -> "Fleet: DEGRADED -- $snap"     to Color.RED
        }
        view.text = text
        view.setTextColor(color)
    }

    private class DashboardRefreshRunnable(
        private val activityRef    : WeakReference<Activity>,
        private val fleetStateRef  : WeakReference<TextView>,
        private val metricsRef     : WeakReference<TextView>,
        private val statusRef      : WeakReference<TextView>,
        private val handler        : Handler,
        private val executor       : ExecutorService
    ) : Runnable {

        override fun run() {
            val activity    = activityRef.get()
            val fleetView   = fleetStateRef.get()
            val metricsView = metricsRef.get()
            val statusView  = statusRef.get()

            if (activity == null || metricsView == null || statusView == null || fleetView == null) {
                return
            }

            executor.execute {
                val metricsText = buildString {
                    append(RuntimeMetricsRegistry.snapshot()).append("\n\n")
                    append(ProcessSurvivalRegistry.snapshot()).append("\n\n")
                    append(ResourceBudgetRegistry.snapshot()).append("\n\n")
                    append(SelfAuditRegistry.snapshot())
                }
                val statusText = ComplianceState.summary(activity)
                val state      = BoosterIgnition.currentState()
                val snap       = BoosterIgnition.fleetSnapshot()

                handler.post {
                    if (metricsView.isAttachedToWindow) {
                        metricsView.text = metricsText
                        statusView.text  = statusText

                        val (text, color) = when (state) {
                            FleetLifecycleState.COLD     -> "Fleet: COLD"              to Color.GRAY
                            FleetLifecycleState.PARTIAL  -> "Fleet: LAUNCHING..."       to Color.YELLOW
                            FleetLifecycleState.WARMING  -> "Fleet: WARMING -- $snap"  to Color.parseColor("#FF8C00")
                            FleetLifecycleState.READY    -> "Fleet: READY -- $snap"    to Color.GREEN
                            FleetLifecycleState.DEGRADED -> "Fleet: DEGRADED -- $snap" to Color.RED
                        }
                        fleetView.text = text
                        fleetView.setTextColor(color)

                        handler.postDelayed(this@DashboardRefreshRunnable, 1000L)
                    }
                }
            }
        }
    }
}
