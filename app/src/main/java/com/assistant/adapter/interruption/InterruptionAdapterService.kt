package com.assistant.adapter.interruption

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.telephony.TelephonyManager
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.notification.NodeNotificationHub
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot
import java.util.concurrent.atomic.AtomicInteger

class InterruptionAdapterService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var interruptionHandler: Handler
    private val errorCount = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    // =========================================================================
    // HARDWORKING ELIMINATORS (Merged from deleted stubs)
    // =========================================================================

    companion object {
        private const val CHANNEL_ID = "splendor_engine_logs"
        private const val WAKELOCK_TAG = "Splendor:InterruptionEliminatorLock"
    }

    object CallAndNotificationEliminator {
        fun updateCallState(state: Int) {
            TelephonyStateRepository.activeCall = state != TelephonyManager.CALL_STATE_IDLE
            CallOverlayRepository.incomingCallVisible = TelephonyStateRepository.activeCall
        }

        fun suppressRingerAndHoldFocus(context: Context) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // 1. Suppress Ringer: Prevents HyperOS from forcing full-screen InCallUI
            // forcing it to use Dynamic Island/banner instead, keeping the game visible.
            try { 
                am.ringerMode = AudioManager.RINGER_MODE_SILENT 
            } catch (_: SecurityException) { 
                // Fallback if DND access is denied
                try { am.setStreamMute(AudioManager.STREAM_RING, true) } catch (_: Throwable) {}
            }

            // 2. Hold Exclusive Audio Focus: Prevents notification sounds from playing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest == null) {
                    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                        .build()
                }
                try { am.requestAudioFocus(focusRequest!!) } catch (_: Throwable) {}
            }
        }

        fun purgeDistractingNotifications(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val myPackage = context.packageName
            
            try {
                // Ensure Splendor-Assist logs bypass DND and are always visible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = nm.getNotificationChannel(CHANNEL_ID)
                    if (channel == null) {
                        val newChannel = NotificationChannel(CHANNEL_ID, "Engine Logs", NotificationManager.IMPORTANCE_HIGH).apply {
                            setBypassDnd(true)
                            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                        }
                        nm.createNotificationChannel(newChannel)
                    } else if (!channel.canBypassDnd()) {
                        channel.setBypassDnd(true)
                        nm.createNotificationChannel(channel)
                    }
                }

                // Kill all non-Splendor notifications to prevent micro-stutters and UI blocks
                nm.activeNotifications.forEach { sbn ->
                    if (sbn.packageName != myPackage) {
                        nm.cancel(sbn.tag, sbn.id)
                    }
                }
            } catch (_: SecurityException) {
                RuntimeLogger.log("Notification purge blocked by OS permissions", "INTERRUPTION")
            }
        }
    }

    object ProcessAndCpuEliminator {
        fun clearBackgroundHogs(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val myPackage = context.packageName
            
            // Kill background processes to free Helio G81-Ultra CPU cores during calls/gameplay
            try {
                val running = am.runningAppProcesses ?: emptyList()
                running.forEach { proc ->
                    if (proc.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        // Keep Splendor, System, and Launcher alive
                        if (!proc.processName.contains(myPackage) && 
                            !proc.processName.contains("launcher") && 
                            !proc.processName.contains("system")) {
                            try { am.killBackgroundProcesses(proc.processName) } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: SecurityException) {}
        }
    }

    // =========================================================================
    // SERVICE LIFECYCLE & LOOP
    // =========================================================================

    private val interruptionRunnable = object : Runnable {
        override fun run() {
            try {
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

                val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("DEPRECATION")
                val callState = try { telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE } catch (_: SecurityException) { TelephonyManager.CALL_STATE_IDLE }

                CallAndNotificationEliminator.updateCallState(callState)

                val state = InterruptionCoordinator.evaluate(batteryLevel, charging, 0)
                InterruptionRepository.save(state)

                // CONDITIONLESS ACTIVE MITIGATION (Every 500ms)
                
                // 1. Purge distracting notifications to prevent micro-stutters
                CallAndNotificationEliminator.purgeDistractingNotifications(this@InterruptionAdapterService)

                // 2. Handle Calls (Prevents full-screen UI and freezes)
                if (callState == TelephonyManager.CALL_STATE_RINGING || TelephonyStateRepository.activeCall) {
                    CallAndNotificationEliminator.suppressRingerAndHoldFocus(this@InterruptionAdapterService)
                    ProcessAndCpuEliminator.clearBackgroundHogs(this@InterruptionAdapterService)
                }

                val throttleMode = when (state.severity) {
                    "CRITICAL" -> "AGGRESSIVE_THROTTLE"
                    "THROTTLE" -> "MODERATE_THROTTLE"
                    "WARNING" -> "LIGHT_THROTTLE"
                    else -> "NORMAL"
                }

                AdapterHealthRegistry.update(
                    AdapterHealthSnapshot(
                        adapterName = "adapter_interruption",
                        status = state.severity,
                        lastHeartbeat = System.currentTimeMillis(),
                        errorCount = errorCount.get(),
                        recoveryCount = 0,
                        details = "battery=${state.batteryLevel},call=${TelephonyStateRepository.activeCall},mode=$throttleMode"
                    )
                )

            } catch (e: Exception) {
                errorCount.incrementAndGet()
                RuntimeLogger.log("InterruptionAdapter heartbeat failed :: ${e.javaClass.simpleName}", "HEALTH")
            } finally {
                try { interruptionHandler.postDelayed(this, 500L) } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogger.log("InterruptionAdapterService started (Hardworking Eliminator Mode)", "ADAPTER")

        // Acquire WakeLock to prevent game freezing during background calls
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours max safety

        workerThread = HandlerThread("InterruptionWorker", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        workerThread.start()
        interruptionHandler = Handler(workerThread.looper)

        NodeNotificationHub.attach(this, "adapter_interruption")
        interruptionHandler.post(interruptionRunnable)
    }

    override fun onDestroy() {
        try { interruptionHandler.removeCallbacks(interruptionRunnable) } catch (_: Exception) {}
        try { workerThread.quitSafely() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        
        NodeNotificationHub.detach(this, "adapter_interruption")
        RuntimeLogger.log("InterruptionAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
