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

object OverloadPlaystyleEngine {

    private val DEFAULT_PITCH_W get() = com.assistant.vision.CameraProfile.captureWidthOrFallback()
    private val DEFAULT_PITCH_H get() = com.assistant.vision.CameraProfile.captureHeightOrFallback()
    private const val ADVANCE_RATIO = 0.18f
    private const val OPPONENT_PRESSURE_THRESHOLD = 0.60f

    private var analyzeCalls: Long = 0L
    private var lastZone: String = "not analyzed yet"
    private var lastStrength: Float = 0f
    private var lastSwitchPlay: Boolean = false
    private var lastUpdatedMs: Long = 0L

    fun analyze(
        ballX: Float,
        ballY: Float,
        playerCount: Int,
        opponentCount: Int,
        defenderDensity: Float,
        laneConfidence: Float,
        weHavePossession: Boolean = false,
        zoneOurs: Int = 0,
        zoneTheirs: Int = 0,
        pitchWidth: Float = DEFAULT_PITCH_W,
        pitchHeight: Float = DEFAULT_PITCH_H
    ): OverloadPlaystyleResult {

        val width = pitchWidth.coerceAtLeast(1f)
        val height = pitchHeight.coerceAtLeast(1f)

        val total = playerCount.coerceAtLeast(0)
        val theirs = opponentCount.coerceIn(0, total)
        val ours = (total - theirs).coerceAtLeast(0)

        // Signed numerical balance in view, -1 (they dominate) .. +1 (we dominate)
        // Prefer real zone counts when supplied; fall back to whole-view totals.
        val zoneTotal = zoneOurs + zoneTheirs
        val balance = if (zoneTotal > 0)
            ((zoneOurs - zoneTheirs).toFloat() / zoneTotal).coerceIn(-1f, 1f)
        else if (total > 0)
            ((ours - theirs).toFloat() / total).coerceIn(-1f, 1f)
        else 0f

        val pressure = defenderDensity.coerceIn(0f, 1f)
        val opponentOverload = balance < 0f || pressure >= OPPONENT_PRESSURE_THRESHOLD

        // Zone is read from lateral ball position (landscape pitch).
        val zone = when {
            total <= 0 -> OverloadZone.NONE
            ballY < height * 0.33f -> OverloadZone.LEFT_WING
            ballY > height * 0.67f -> OverloadZone.RIGHT_WING
            else -> OverloadZone.CENTRAL
        }

        // Strength blends numerical edge with how contested the zone is.
        val contested = if (opponentOverload) pressure else (1f - pressure)
        val overloadStrength =
            (abs(balance) * 0.65f + contested * 0.35f).coerceIn(0f, 1f)

        val switchPlay = opponentOverload && zone != OverloadZone.NONE

        val advance = width * ADVANCE_RATIO
        val swarm = !weHavePossession
        val exploitX = if (swarm) ballX.coerceIn(0f, width)
                       else (ballX + advance).coerceIn(0f, width)

        // Switch play mirrors to the far side; otherwise press the loaded zone.
        val exploitY = if (swarm) ballY.coerceIn(0f, height)
        else if (switchPlay) {
            (height - ballY).coerceIn(0f, height)
        } else {
            when (zone) {
                OverloadZone.LEFT_WING -> (ballY - height * 0.05f).coerceIn(0f, height)
                OverloadZone.RIGHT_WING -> (ballY + height * 0.05f).coerceIn(0f, height)
                else -> ballY.coerceIn(0f, height)
            }
        }

        val confidence =
            (laneConfidence.coerceIn(0f, 1f) * 0.7f + overloadStrength * 0.3f)
                .coerceIn(0f, 1f)

        // DEFENSIVE SWARM is the primary playstyle: opponent holds the ball, our
        // players collapse on the carrier to deny build-up. ATTACKING_EXPLOIT is
        // the hybrid inverse used when we regain possession.
        val mode = when {
            zone == OverloadZone.NONE -> OverloadMode.IDLE
            !weHavePossession -> OverloadMode.DEFENSIVE_SWARM
            else -> OverloadMode.ATTACKING_EXPLOIT
        }

        record(zone.name, overloadStrength, switchPlay)

        return OverloadPlaystyleResult(
            mode = mode,
            zone = zone,
            overloadStrength = overloadStrength,
            opponentOverload = opponentOverload,
            switchPlayRecommended = switchPlay,
            exploitX = exploitX,
            exploitY = exploitY,
            confidence = confidence
        )
    }

    @Synchronized
    private fun record(zone: String, strength: Float, switchPlay: Boolean) {
        analyzeCalls += 1L
        lastZone = zone
        lastStrength = strength
        lastSwitchPlay = switchPlay
        lastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun overloadRuntimeSnapshot(): Map<String, Any> = mapOf(
        "analyzeCalls" to analyzeCalls,
        "lastZone" to lastZone,
        "lastStrength" to lastStrength,
        "lastSwitchPlay" to lastSwitchPlay,
        "lastUpdatedMs" to lastUpdatedMs
    )

    @Synchronized
    fun reset() {
        analyzeCalls = 0L
        lastZone = "not analyzed yet"
        lastStrength = 0f
        lastSwitchPlay = false
        lastUpdatedMs = 0L
    }
}
/* ======
OverloadPlaystyleEngine Anchor
====== */

/* ========
PassingLaneGraphEngine
======== */
