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

object AttackAxisModel {
    @Volatile private var direction: Int = 0
    @Volatile private var confidence: Float = 0f
    private var previousBallX: Float = 0f
    private var previousFrame: Long = -1L
    private var previousBallValid: Boolean = false
    private var motionDirection: Int = 0
    private var motionStreak: Int = 0

    @Synchronized fun observe(scene: SceneSnapshot, userHasPossession: Boolean, screenWidth: Float) {
        var userX = 0f
        var userCount = 0
        val players = scene.trackedPlayers
        for (i in players.indices) {
            val player = players[i]
            if (player.isUserTeam && player.x.isFinite() && player.confidence >= 0.20f) {
                userX += player.x
                userCount++
            }
        }
        if (userCount > 0) userX /= userCount.toFloat()

        if (scene.goalDetected && scene.goalConfidence >= 0.45f &&
            scene.goalRightX > scene.goalLeftX && userCount > 0) {
            val goalX = (scene.goalLeftX + scene.goalRightX) * 0.5f
            val delta = goalX - userX
            val minimumSeparation = screenWidth * 0.035f
            if (delta.isFinite() && kotlin.math.abs(delta) >= minimumSeparation) {
                direction = if (delta > 0f) 1 else -1
                confidence = scene.goalConfidence.coerceIn(0f, 1f)
            }
        } else if (userHasPossession && scene.trackedBallVisible && userCount > 0) {
            val consecutive = previousBallValid && scene.frameNumber == previousFrame + 1L
            val delta = if (consecutive) scene.trackedBallX - previousBallX else 0f
            val minimumStep = (screenWidth * 0.003f).coerceAtLeast(3f)
            val candidate = when {
                delta >= minimumStep -> 1
                delta <= -minimumStep -> -1
                else -> 0
            }
            if (candidate != 0) {
                if (candidate == motionDirection) motionStreak++ else {
                    motionDirection = candidate
                    motionStreak = 1
                }
                val shapeSupports = candidate.toFloat() * (scene.trackedBallX - userX) >= -(screenWidth * 0.10f)
                if (motionStreak >= 3 && shapeSupports) {
                    direction = candidate
                    confidence = (0.55f + (motionStreak - 3).coerceAtMost(3) * 0.06f).coerceAtMost(0.73f)
                }
            } else if (consecutive) {
                motionStreak = 0
            }
        }

        previousBallValid = scene.trackedBallVisible
        previousBallX = scene.trackedBallX
        previousFrame = scene.frameNumber
    }

    fun directionIfConfident(): Int = if (confidence >= 0.62f) direction else 0
}

/* ========
ActiveAttackerEngine
======== */
