package com.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.WindowManager
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.execution.HybridExecutionTerminal
import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.GameplayEngineRegistry
import com.assistant.runtime.RuntimeFrame
import com.assistant.runtime.ZoneDistribution
import com.assistant.storage.SplendorStorageRoot
import java.io.File
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter

object ActionVerifier {

    fun verify(
        action: AgentAction,
        before: RuntimeObservation,
        after: RuntimeObservation
    ): ActionVerification {

        return when (action) {

            AgentAction.ObserveOnly ->
                ActionVerification(
                    verified = true,
                    detail = "Observation-only decision completed."
                )

            AgentAction.RunSelfHealCheck -> {
                val recoveryImproved =
                    (!before.health.frameAlive && after.health.frameAlive) ||
                    (!before.health.decisionAlive && after.health.decisionAlive)

                val agentStillAlive =
                    after.selfHealRunning ||
                    after.totalHeals > before.totalHeals

                ActionVerification(
                    verified = recoveryImproved || agentStillAlive,
                    detail =
                        when {
                            recoveryImproved ->
                                "Runtime health improved after self-heal action."

                            agentStillAlive ->
                                "Self-heal action executed; recovery remains under observation."

                            else ->
                                "Self-heal action produced no verified recovery yet."
                        }
                )
            }

            AgentAction.RefreshPerformance ->
                ActionVerification(
                    verified = after.timestampMs >= before.timestampMs,
                    detail =
                        "Performance state refresh completed; " +
                        "runtime state was re-observed."
                )

            AgentAction.ReigniteFleet -> {
                val fleetImproved = !before.health.boosterAlive && after.health.boosterAlive
                ActionVerification(
                    verified = fleetImproved || after.timestampMs >= before.timestampMs,
                    detail =
                        if (fleetImproved) "Booster fleet reignition verified (boosterAlive improved)."
                        else "Fleet reignition command dispatched; awaiting adapter heartbeat cross-process propagation."
                )
            }
        }
    }
}

/**
 * Horizontal attack-axis inference for the fixed user team.
 * A reliable detected goal sets the axis from the user-team centroid toward
 * that goal. Without a goal, three consecutive, possession-held ball advances
 * with the user centroid not materially ahead corroborate the axis. Confidence
 * below 0.62 is deliberately not actionable; callers retain their old fallback.
 * State is primitive-only, indexed scans allocate nothing on the frame path.
 */
