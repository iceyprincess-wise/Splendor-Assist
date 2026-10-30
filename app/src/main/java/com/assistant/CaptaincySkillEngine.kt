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

object CaptaincySkillEngine {

    // Fatigue proxy: below this floor, fatigue is negligible, skip
    private const val FATIGUE_ACTIVATION_FLOOR = 0.20f

    // Team lift range
    private const val MIN_TEAM_LIFT   = 1.00f
    private const val MAX_TEAM_LIFT   = 1.15f
    private const val PANIC_TEAM_LIFT = 1.20f   // peak: captain in full crisis

    // Composure gesture bonus range (ms)
    private const val MIN_COMPOSURE_MS = 0L
    private const val MAX_COMPOSURE_MS = 8L

    // Fatigue proxy component weights (sum = 1.0)
    private const val DENSITY_WEIGHT = 0.50f   // sustained defensive pressure
    private const val PANIC_WEIGHT   = 0.30f   // team at breaking point
    private const val VISION_WEIGHT  = 0.20f   // degraded decision quality

    private val activations = AtomicLong(0L)

    @Volatile private var lastFatigueProxy  = 0f
    @Volatile private var lastTeamLift      = MIN_TEAM_LIFT
    @Volatile private var lastComposureMs   = MIN_COMPOSURE_MS
    @Volatile private var captainDesignated = false
    @Volatile private var prefs: android.content.SharedPreferences? = null
    @Volatile private var active            = false
    @Volatile private var lastUpdatedMs     = 0L

    data class CaptaincyResult(
        val active: Boolean,

        /**
         * Estimated team fatigue level [0,1].
         * Derived from: defender density + panic state + vision confidence.
         */
        val fatigueProxy: Float,

        /**
         * Confidence multiplier applied post-arbitration [1.00, 1.20].
         * 1.00 = no lift (low fatigue). 1.20 = panic-level lift.
         * Applied to lastWeight in RuntimeDecisionLoop for visibility.
         */
        val teamLiftFactor: Float,

        /**
         * Additional gesture duration [0, 8] ms.
         * Captain-inspired teams make more deliberate, composed inputs.
         */
        val composureBoostMs: Long,

        /**
         * True when captain lift is significant (>= 1.10x).
         */
        val squadInspired: Boolean
    )

    fun evaluate(frame: RuntimeFrame): CaptaincyResult {
        if (!captainDesignated) return inert()
        if (!frame.trusted) {
            active         = false
            lastFatigueProxy = 0f
            lastTeamLift   = MIN_TEAM_LIFT
            lastComposureMs = MIN_COMPOSURE_MS
            return inert()
        }

        // ── Fatigue proxy ─────────────────────────────────────────────────
        // High defender density: team under sustained press → faster drain
        // Panic: team at breaking point → peak fatigue symptom
        // Low confidence: vision/decision quality degrading → fatigue indicator
        val densityComponent = frame.defenderDensity.coerceIn(0f, 1f) * DENSITY_WEIGHT
        val panicComponent   = if (frame.panic) PANIC_WEIGHT else 0f
        val visionComponent  = (1f - frame.confidence.coerceIn(0f, 1f)) * VISION_WEIGHT

        val fatigueProxy = (densityComponent + panicComponent + visionComponent)
            .coerceIn(0f, 1f)

        lastFatigueProxy = fatigueProxy

        if (fatigueProxy < FATIGUE_ACTIVATION_FLOOR) {
            active          = false
            lastTeamLift    = MIN_TEAM_LIFT
            lastComposureMs = MIN_COMPOSURE_MS
            return inert()
        }

        // ── Team lift ─────────────────────────────────────────────────────
        // Panic → fixed peak lift (captain most vocal in a crisis).
        // Otherwise → linear scale with fatigue above activation floor.
        val teamLiftFactor: Float = when {
            frame.panic -> PANIC_TEAM_LIFT
            else -> {
                val normalized = ((fatigueProxy - FATIGUE_ACTIVATION_FLOOR) /
                    (1f - FATIGUE_ACTIVATION_FLOOR)).coerceIn(0f, 1f)
                (MIN_TEAM_LIFT + normalized * (MAX_TEAM_LIFT - MIN_TEAM_LIFT))
                    .coerceIn(MIN_TEAM_LIFT, MAX_TEAM_LIFT)
            }
        }

        // ── Composure bonus ───────────────────────────────────────────────
        // Tired teams rush; captain steadies them → more deliberate inputs.
        // Scales with fatigue proxy so the bonus grows as fatigue grows.
        val composureNorm  = ((fatigueProxy - FATIGUE_ACTIVATION_FLOOR) /
            (1f - FATIGUE_ACTIVATION_FLOOR)).coerceIn(0f, 1f)
        val composureBoostMs = (composureNorm * MAX_COMPOSURE_MS)
            .toLong().coerceIn(MIN_COMPOSURE_MS, MAX_COMPOSURE_MS)

        val squadInspired = teamLiftFactor >= 1.10f

        lastTeamLift    = teamLiftFactor
        lastComposureMs = composureBoostMs
        lastUpdatedMs   = System.currentTimeMillis()
        active          = true

        val count = activations.incrementAndGet()
        if (count % 30L == 0L) {
            RuntimeLogger.log(
                "CAPTAINCY ACTIVE: fatigue=%.2f lift=%.3f composure=+%dms inspired=%b #%d"
                    .format(fatigueProxy, teamLiftFactor, composureBoostMs, squadInspired, count),
                "CAPTAINCY"
            )
        }

        return CaptaincyResult(
            active           = true,
            fatigueProxy     = fatigueProxy,
            teamLiftFactor   = teamLiftFactor,
            composureBoostMs = composureBoostMs,
            squadInspired    = squadInspired
        )
    }

    fun setCaptainDesignated(enabled: Boolean) {
        captainDesignated = enabled
        try { prefs?.edit()?.putBoolean("captaincy_designated", enabled)?.apply() } catch (_: Throwable) {}
    }

    // V6 FIX (field bug: switch showed OFF while engine stayed ON).
    fun isDesignated(): Boolean = captainDesignated

    fun init(context: android.content.Context) {
        try {
            val p = context.applicationContext.getSharedPreferences("splendor_engine_toggles", 0)
            prefs = p
            captainDesignated = p.getBoolean("captaincy_designated", captainDesignated)
        } catch (_: Throwable) {}
    }

    fun isActive(): Boolean = active

    fun diagnostics(): Map<String, Any> = mapOf(
        "captainDesignated" to captainDesignated,
        "active"           to active,
        "activations"      to activations.get(),
        "lastFatigueProxy" to lastFatigueProxy,
        "lastTeamLift"     to lastTeamLift,
        "lastComposureMs"  to lastComposureMs,
        "lastUpdatedMs"    to lastUpdatedMs
    )

    private fun inert() = CaptaincyResult(
        active           = false,
        fatigueProxy     = lastFatigueProxy,
        teamLiftFactor   = MIN_TEAM_LIFT,
        composureBoostMs = MIN_COMPOSURE_MS,
        squadInspired    = false
    )

    fun reset() {
        activations.set(0L)
        lastFatigueProxy  = 0f
        lastTeamLift      = MIN_TEAM_LIFT
        lastComposureMs   = MIN_COMPOSURE_MS
        active            = false
        lastUpdatedMs     = 0L
    }
}

/* ========
CentralOverloadDetectionEngine
======== */
