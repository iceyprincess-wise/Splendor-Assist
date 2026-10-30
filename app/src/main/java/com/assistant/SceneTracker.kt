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

object SceneTracker {

    private var frameCounter = 0L

    private var trackedBallX = 0f
    private var trackedBallY = 0f
    private var trackedBallSpeed = 0f

    private var latest =
        SceneSnapshot()

    private val trackedPlayers = mutableListOf<TrackedPlayer>()

    private val mutationLock = Any()

    private var trackedGoalkeeper: TrackedPlayer? = null

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val PLAYER_MEMORY_FRAMES: Long
        get() = 15L
    private val MAX_TRACKS: Int
        get() = 30

    private const val CONFIDENCE_DECAY = 0.05f

    fun update(
        state: GameStateSnapshot,
        players: PlayerDetectionResult
    ): SceneSnapshot = synchronized(mutationLock) {

        frameCounter++

        trackedPlayers.forEach {
            it.confidence = (it.confidence - CONFIDENCE_DECAY).coerceAtLeast(0f)
        }

        trackedBallX = state.ballX
        trackedBallY = state.ballY
        trackedBallSpeed = state.ballSpeed

        EntityAssociationEngine.associate(
            trackedPlayers,
            players.detections,
            frameCounter
        )

        if (state.goalkeeperDetected) {

            if (trackedGoalkeeper == null) {

                trackedGoalkeeper =
                    TrackedPlayer(
                        id = -1,

                        x = state.goalkeeperX,
                        y = state.goalkeeperY,

                        velocityX = 0f,
                        velocityY = 0f,

                        confidence = state.goalkeeperConfidence,

                        isUserTeam = true,
                        isGoalkeeper = true,

                        lastSeenFrame = frameCounter
                    )

            } else {

                val keeper = trackedGoalkeeper!!

                keeper.velocityX =
                    state.goalkeeperX - keeper.x

                keeper.velocityY =
                    state.goalkeeperY - keeper.y

                keeper.headingRadians =
                    kotlin.math.atan2(
                        keeper.velocityY,
                        keeper.velocityX
                    )

                keeper.x = state.goalkeeperX
                keeper.y = state.goalkeeperY

                keeper.confidence =
                    state.goalkeeperConfidence

                keeper.lastSeenFrame =
                    frameCounter
            }
        }

        // prune the dead: unseen too long or fully faded
        val memory = PLAYER_MEMORY_FRAMES
        trackedPlayers.removeAll {
            (frameCounter - it.lastSeenFrame) > memory ||
            it.confidence <= 0f
        }

        // sanity cap: keep the freshest/strongest tracks only, so crowd-pixel
        // noise cannot inflate the count past what a real pitch can hold
        val cap = if (MAX_TRACKS < 1) 1 else MAX_TRACKS
        if (trackedPlayers.size > cap) {
            trackedPlayers.sortWith(
                compareByDescending<TrackedPlayer> { it.lastSeenFrame }
                    .thenByDescending { it.confidence }
            )
            while (trackedPlayers.size > cap) {
                trackedPlayers.removeAt(trackedPlayers.size - 1)
            }
        }

        latest =
            SceneSnapshot(
                frameNumber = frameCounter,

                ballVisible =
                    state.ballDetected,

                playerCount =
                    state.userPlayers +
                    state.opponentPlayers,

                userPlayers =
                    state.userPlayers,

                opponentPlayers =
                    state.opponentPlayers,

                trackedBallX = trackedBallX,

                trackedBallY = trackedBallY,

                trackedBallSpeed = trackedBallSpeed,

                trackedBallVisible = state.ballDetected,

                goalkeeperVisible =
                    trackedGoalkeeper != null,

                goalkeeperX =
                    trackedGoalkeeper?.x ?: 0f,

                goalkeeperY =
                    trackedGoalkeeper?.y ?: 0f,

                goalkeeperHeading =
                    trackedGoalkeeper?.headingRadians ?: 0f,

                goalDetected = state.goalDetected,
                goalLeftX = state.goalLeftX,
                goalRightX = state.goalRightX,
                goalTopY = state.goalTopY,
                goalBottomY = state.goalBottomY,
                goalConfidence = state.goalConfidence,

                touchLinesDetected = state.touchLinesDetected,
                penaltyAreaDetected = state.penaltyAreaDetected,
                goalAreaDetected = state.goalAreaDetected,
                centerCircleDetected = state.centerCircleDetected,
                fieldConfidence = state.fieldConfidence,

                trackedPlayers = trackedPlayers.toList(),

                confidence = state.confidence
            )

        latest
    }

    fun current(): SceneSnapshot =
        latest
}

/* ========
SmartAssistMetrics
======== */
