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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

    companion object {
        private const val CHANNEL_ID = "splendor_engine_logs"
        private const val WAKELOCK_TAG = "Splendor:DozeBypassLock"
    }

    // =========================================================================
    // 4 HARDWORKING ELIMINATORS (Companion Objects per CI-T&C Rule #7)
    // =========================================================================

    object DozeBypassEliminator {
        private var wakeLock: PowerManager.WakeLock? = null
        
        fun ignite(context: Context) {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours safety cap
                RuntimeLogger.log("DozeBypassEliminator: PARTIAL_WAKE_LOCK acquired. Doze disabled.", "INTERRUPTION")
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
        }

        fun extinguish() {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        }
    }

    object NetworkPriorityHijacker {
        private var bound = false
        
        fun execute(context: Context) {
            if (bound) return
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build()
                
                cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        try { 
                            cm.bindProcessToNetwork(network)
                            RuntimeLogger.log("NetworkPriorityHijacker: Process bound to FOREGROUND network.", "INTERRUPTION")
                        } catch (_: Throwable) {}
                    }
                })
                bound = true
            } catch (_: Throwable) {}
        }

        fun unbind(context: Context) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.bindProcessToNetwork(null)
                bound = false
            } catch (_: Throwable) {}
        }
    }

    object NotificationHardKiller {
        fun execute(context: Context) {
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

                // Kill all non-Splendor active notifications to prevent micro-stutters and UI blocks
                nm.activeNotifications.forEach { sbn ->
                    if (sbn.packageName != myPackage) {
                        nm.cancel(sbn.tag, sbn.id)
                    }
                }
            } catch (_: SecurityException) {}
        }
    }

    object BackgroundProcessPurger {
        fun execute(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val myPackage = context.packageName
            
            try {
                val running = am.runningAppProcesses ?: emptyList()
                running.forEach { proc ->
                    if (proc.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        if (!proc.processName.contains(myPackage) && 
                            !proc.processName.contains("system") && 
                            !proc.processName.contains("launcher")) {
                            try { 
                                am.killBackgroundProcesses(proc.processName) 
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: SecurityException) {}
        }
    }

    object CallAndAudioEliminator {
        fun updateCallState(state: Int) {
            TelephonyStateRepository.activeCall = state != TelephonyManager.CALL_STATE_IDLE
            CallOverlayRepository.incomingCallVisible = TelephonyStateRepository.activeCall
        }

        private var focusRequest: AudioFocusRequest? = null

        fun suppressRingerAndHoldFocus(context: Context) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            try { 
                am.ringerMode = AudioManager.RINGER_MODE_SILENT 
            } catch (_: SecurityException) { 
                try { am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) } catch (_: Throwable) {}
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest == null) {
                    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                        .build()
                }
                try { am.requestAudioFocus(focusRequest!!) } catch (_: Throwable) {}
            }
        }
    }

    // =========================================================================
    // SERVICE LIFECYCLE & LOOP
    // =========================================================================

    private val interruptionRunnable = object : Runnable {
        override fun run() {
            try {
                // V6 ROOT-CAUSE FIX (field logs: "heartbeat failed :: SecurityException"
                // every 500ms -> no persisted heartbeat -> booster-not-ready forever).
                // Heartbeat FIRST, independently guarded: the cross-process truth the
                // booster gate/health reads must survive any eliminator fault.
                // V12 ROOT-CAUSE FIX: registerReceiver can throw SecurityException on
                // Android 14+/HyperOS when called from background service. If it throws,
                // the entire try block aborted and the heartbeat was NEVER published,
                // causing boosterAlive=false and permanent booster-not-ready degradation.
                // FIX: Wrap registerReceiver individually. Use defaults if it fails.
                // The heartbeat MUST publish regardless of battery read success.
                var batteryLevel = -1
                var charging = false
                try {
                    val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
                } catch (_: SecurityException) {
                    // Battery read blocked by OS; proceed with defaults to keep heartbeat alive
                } catch (_: Throwable) {}

                try {
                    val state = InterruptionCoordinator.evaluate(batteryLevel, charging, 0)
                    InterruptionRepository.save(state)
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
                }

                // Eliminators: each individually guarded so one SecurityException
                // can never kill the loop or the heartbeat again.
                try { DozeBypassEliminator.ignite(this@InterruptionAdapterService) } catch (_: Throwable) {}
                try { NetworkPriorityHijacker.execute(this@InterruptionAdapterService) } catch (_: Throwable) {}
                try { NotificationHardKiller.execute(this@InterruptionAdapterService) } catch (_: Throwable) {}
                try { BackgroundProcessPurger.execute(this@InterruptionAdapterService) } catch (_: Throwable) {}

                val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("DEPRECATION")
                val callState = try { telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE } catch (_: SecurityException) { TelephonyManager.CALL_STATE_IDLE }

                CallAndAudioEliminator.updateCallState(callState)

                if (callState == TelephonyManager.CALL_STATE_RINGING || TelephonyStateRepository.activeCall) {
                    CallAndAudioEliminator.suppressRingerAndHoldFocus(this@InterruptionAdapterService)
                }

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
        RuntimeLogger.log("InterruptionAdapterService started (4-Eliminator Hardworking Mode)", "ADAPTER")

        workerThread = HandlerThread("InterruptionWorker", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        workerThread.start()
        interruptionHandler = Handler(workerThread.looper)

        NodeNotificationHub.attach(this, "adapter_interruption")
        interruptionHandler.post(interruptionRunnable)
    }

    override fun onDestroy() {
        try { interruptionHandler.removeCallbacks(interruptionRunnable) } catch (_: Exception) {}
        try { workerThread.quitSafely() } catch (_: Exception) {}
        
        DozeBypassEliminator.extinguish()
        NetworkPriorityHijacker.unbind(this)
        
        NodeNotificationHub.detach(this, "adapter_interruption")
        RuntimeLogger.log("InterruptionAdapter heartbeat stopped", "HEALTH")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
