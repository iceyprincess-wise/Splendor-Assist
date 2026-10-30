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

object EntityAssociationEngine {

    private var nextTrackId = 1

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val MAX_ASSOCIATION_DISTANCE: Float
        get() = 120f
    private val DUPLICATE_TRACK_DISTANCE: Float
        get() = 12f

    private const val VELOCITY_SMOOTHING = 0.35f
    private const val CONFIDENCE_SMOOTHING = 0.25f

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun associate(
        trackedPlayers: MutableList<TrackedPlayer>,
        detections: List<PlayerDetection>,
        frameNumber: Long
    ) {
        val assigned = mutableSetOf<Int>()
        val maxDist = MAX_ASSOCIATION_DISTANCE

        // 1. Each track takes its nearest unassigned detection ONLY if it is
        //    inside the gate. A far detection stays available to spawn its
        //    own track instead of being consumed and thrown away.
        for (track in trackedPlayers) {
            var bestIdx = -1
            var bestD = Float.MAX_VALUE
            for (i in detections.indices) {
                if (i in assigned) continue
                val d = distance(track.x, track.y, detections[i].x, detections[i].y)
                if (d < bestD) {
                    bestD = d
                    bestIdx = i
                }
            }

            if (bestIdx >= 0 && bestD <= maxDist) {
                assigned.add(bestIdx)
                val nearest = detections[bestIdx]

                track.velocityX =
                    track.velocityX * (1f - VELOCITY_SMOOTHING) +
                    (nearest.x - track.x) * VELOCITY_SMOOTHING
                track.velocityY =
                    track.velocityY * (1f - VELOCITY_SMOOTHING) +
                    (nearest.y - track.y) * VELOCITY_SMOOTHING

                track.headingRadians =
                    kotlin.math.atan2(track.velocityY, track.velocityX)

                track.x = nearest.x
                track.y = nearest.y

                track.confidence = (
                    track.confidence * (1f - CONFIDENCE_SMOOTHING) +
                    nearest.confidence * CONFIDENCE_SMOOTHING
                ).coerceIn(0f, 1f)

                track.lastSeenFrame = frameNumber
            } else {
                // nothing believable this frame: coast and fade
                track.x += track.velocityX
                track.y += track.velocityY
                track.confidence = (track.confidence * 0.98f).coerceAtLeast(0f)
            }
        }

        // 2. Detections no track claimed become new tracks.
        for (i in detections.indices) {
            if (i in assigned) continue
            val detection = detections[i]
            trackedPlayers.add(
                TrackedPlayer(
                    id = nextTrackId++,
                    x = detection.x,
                    y = detection.y,
                    velocityX = 0f,
                    velocityY = 0f,
                    confidence = detection.confidence,
                    isUserTeam = detection.isUserTeam,
                    lastSeenFrame = frameNumber
                )
            )
        }

        // 3. Deterministic duplicate merge: two tracks on the same spot are
        //    one player. Stronger confidence wins; fresher sighting breaks
        //    ties; identity-based set so equal pairs lose exactly one member.
        val dupDist = DUPLICATE_TRACK_DISTANCE
        val doomed = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<TrackedPlayer, Boolean>()
        )
        for (i in trackedPlayers.indices) {
            val a = trackedPlayers[i]
            if (a in doomed) continue
            for (j in i + 1 until trackedPlayers.size) {
                val b = trackedPlayers[j]
                if (b in doomed) continue
                if (distance(a.x, a.y, b.x, b.y) < dupDist) {
                    val loser = when {
                        a.confidence != b.confidence ->
                            if (a.confidence < b.confidence) a else b
                        a.lastSeenFrame != b.lastSeenFrame ->
                            if (a.lastSeenFrame < b.lastSeenFrame) a else b
                        else -> b
                    }
                    doomed.add(loser)
                    if (loser === a) break
                }
            }
        }
        if (doomed.isNotEmpty()) {
            val it = trackedPlayers.iterator()
            while (it.hasNext()) {
                if (it.next() in doomed) it.remove()
            }
        }
    }
}

/* ========
FPSMonitor
======== */
