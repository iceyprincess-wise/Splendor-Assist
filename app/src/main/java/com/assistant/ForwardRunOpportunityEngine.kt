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

object ForwardRunOpportunityEngine {

    fun evaluate(
        distance:Float,
        strength:Int
    ):ForwardRunResult {

        val factor=
            strength.coerceIn(0,100)/100f

        return ForwardRunResult(
            runBoost=
                1f + (factor * 0.80f),

            laneScore=
                (distance/1000f)
                    .coerceAtMost(1f),

            confidence=
                (0.65f + factor*0.35f)
                    .coerceAtMost(1f)
        )
    }
}

/* ========
FrameAssembler
======== */
/*
 * Reads the state stores ONCE per capture and produces one immutable frame.
 * Engines must never touch these stores directly after this exists — they
 * receive this frame instead. Guarded per-store so one missing store cannot
 * abort frame assembly.
 *
 * REPAIRED (Task B): hasBall was derived from raw stored coordinates, so
 * once a ball had EVER been seen the flag stayed true forever. hasBall now
 * requires the sighting to be FRESH (VisionTrust age-decay window).
 *
 * REPAIRED (Task C round 1): frame confidence was crossing-lane-only,
 * zeroing the whole contributor stack in any phase without a viable
 * crossing lane. Confidence now follows fresh ball trust.
 *
 * REPAIRED (Task C round 2 - FIELD-LOG PROVEN): hasBall meant "ball is
 * VISIBLE", not "WE possess it". The 18:38 field session shows the result:
 * hasBall=true for essentially the whole match, so every contributor gated
 * on !hasBall - ThreatPriority, CrossClaim, KeeperBias, PanicSave,
 * BallPress, and with them tackling/interception behaviour - recorded
 * ZERO contributions all session while the attack-side engines ran 6574
 * cycles. The keeper wasn't dying; it was never allowed to speak because
 * "ball on screen" was read as "we have it".
 *
 * Fix: hasBall now follows the REAL possession verdict from
 * BallPossessionEngine (via Phase3WorldState) whenever that verdict has
 * usable confidence; the fresh-sighting rule remains as fallback only when
 * possession has no data yet (cold start). Admin-tunable floor:
 *   assist.possession.min_conf (default 0.20)
 */
