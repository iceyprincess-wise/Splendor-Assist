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

object FightingSpiritEngine {

    private const val PRESSURE_FLOOR = 0.55f
    private const val MAX_PRESSURE = 1.0f
    private const val MIN_ACCURACY_RETENTION = 1.0f
    private const val MAX_ACCURACY_RETENTION = 1.20f
    private const val ACTIVE_PANIC_RETENTION = 1.10f
    private const val COOLDOWN_MS = 800L

    private val activations = AtomicLong(0L)

    @Volatile
    private var lastActivationMs = 0L

    @Volatile
    private var lastRetention = MIN_ACCURACY_RETENTION

    @Volatile
    private var lastPressure = 0.0f

    @Volatile
    private var active = false

    data class FightingSpiritResult(
        val active: Boolean,

        /**
         * Compatibility field retained for existing arbitration callers.
         *
         * Fighting Spirit itself does not grant generic action authority,
         * so this remains 1.0f. The skill effect belongs in pressure
         * accuracy retention instead.
         */
        val authorityBoost: Float,

        /**
         * Pressure penalty resistance.
         *
         * 0.50f = normal pressure penalty resistance baseline.
         * 0.75f = stronger resilience when Fighting Spirit is active.
         */
        val panicResistance: Float,

        /**
         * Compatibility field retained for existing callers.
         *
         * Fighting Spirit does not increase gesture duration.
         */
        val durationBoostMs: Long,

        /**
         * Multiplier describing how much pressure-induced accuracy
         * degradation should be retained by the downstream action model.
         *
         * 1.0f = no additional retention.
         * Up to 1.20f = strongest locally inferred pressure resilience.
         */
        val accuracyRetention: Float,

        /**
         * Normalized local pressure estimate used by this engine.
         */
        val pressure: Float
    )

    /**
     * Evaluate Fighting Spirit from the current trusted RuntimeFrame.
     *
     * The skill is modelled as passive resilience under pressure rather
     * than as a burst of generic execution power.
     */
    fun evaluate(frame: RuntimeFrame): FightingSpiritResult {
        if (!frame.trusted) {
            active = false
            lastPressure = 0.0f
            lastRetention = MIN_ACCURACY_RETENTION
            return inert()
        }

        val density = frame.defenderDensity.coerceIn(0.0f, 1.0f)

        /*
         * defenderDensity is the available local proxy for nearby
         * opposition pressure. Panic contributes a bounded secondary
         * signal rather than creating an unrelated power multiplier.
         */
        val panicPressure = if (frame.panic) 0.15f else 0.0f

        val pressure = (density + panicPressure).coerceIn(0.0f, MAX_PRESSURE)

        if (pressure < PRESSURE_FLOOR) {
            active = false
            lastPressure = pressure
            lastRetention = MIN_ACCURACY_RETENTION
            return inert()
        }

        val now = System.currentTimeMillis()

        if (now - lastActivationMs < COOLDOWN_MS) {
            return resultFromState()
        }

        val normalized =
            ((pressure - PRESSURE_FLOOR) /
                (MAX_PRESSURE - PRESSURE_FLOOR))
                .coerceIn(0.0f, 1.0f)

        /*
         * Continuous retention curve:
         *
         * floor pressure -> 1.00x
         * maximum pressure -> 1.20x
         *
         * This is a local modelling coefficient, not a claim that KONAMI
         * uses this exact multiplier internally.
         */
        val retention =
            (MIN_ACCURACY_RETENTION +
                normalized *
                (MAX_ACCURACY_RETENTION - MIN_ACCURACY_RETENTION))
                .coerceIn(
                    MIN_ACCURACY_RETENTION,
                    MAX_ACCURACY_RETENTION
                )

        val panicResistance =
            if (frame.panic) ACTIVE_PANIC_RETENTION
            else 0.75f

        lastActivationMs = now
        lastPressure = pressure
        lastRetention = retention
        active = true

        val count = activations.incrementAndGet()

        RuntimeLogger.log(
            "FIGHTING_SPIRIT ACTIVE: pressure=%.2f retention=%.3f panic=%b activation=#%d"
                .format(
                    pressure,
                    retention,
                    frame.panic,
                    count
                ),
            "FIGHTING_SPIRIT"
        )

        return FightingSpiritResult(
            active = true,
            authorityBoost = 1.0f,
            panicResistance = panicResistance,
            durationBoostMs = 0L,
            accuracyRetention = retention,
            pressure = pressure
        )
    }

    fun isActive(): Boolean = active

    fun diagnostics(): Map<String, Any> = mapOf(
        "active" to active,
        "activations" to activations.get(),
        "lastRetention" to lastRetention,
        "lastPressure" to lastPressure,
        "lastActivationMs" to lastActivationMs
    )

    private fun resultFromState(): FightingSpiritResult {
        return FightingSpiritResult(
            active = active,
            authorityBoost = 1.0f,
            panicResistance = if (active) 0.75f else 0.5f,
            durationBoostMs = 0L,
            accuracyRetention = lastRetention,
            pressure = lastPressure
        )
    }

    private fun inert(): FightingSpiritResult {
        return FightingSpiritResult(
            active = false,
            authorityBoost = 1.0f,
            panicResistance = 0.5f,
            durationBoostMs = 0L,
            accuracyRetention = MIN_ACCURACY_RETENTION,
            pressure = lastPressure
        )
    }

    fun reset() {
        activations.set(0L)
        lastActivationMs = 0L
        lastRetention = MIN_ACCURACY_RETENTION
        lastPressure = 0.0f
        active = false
    }
}

/* ========
FormationAdaptationEngine
======== */
