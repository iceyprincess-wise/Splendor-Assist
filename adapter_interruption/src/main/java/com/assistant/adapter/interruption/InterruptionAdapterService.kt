package com.assistant.adapter.interruption

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.telephony.TelephonyManager
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.registry.AdapterHealthRegistry
import com.assistant.diagnostic.registry.AdapterHealthSnapshot

class InterruptionAdapterService : Service() {

    private lateinit var workerThread: HandlerThread

    private lateinit var interruptionHandler: Handler

    private val interruptionRunnable =
        object : Runnable {

            override fun run() {
                try {
                    val batteryIntent =
                        registerReceiver(
                            null,
                            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                        )

                    val batteryLevel =
                        batteryIntent?.getIntExtra(
                            BatteryManager.EXTRA_LEVEL,
                            -1
                        ) ?: -1

                    val charging =
                        batteryIntent?.getIntExtra(
                            BatteryManager.EXTRA_STATUS,
                            -1
                        ) == BatteryManager.BATTERY_STATUS_CHARGING

                    val telephonyManager =
                        getSystemService(
                            TELEPHONY_SERVICE
                        ) as? TelephonyManager

                    val callState =
                        try {
                            telephonyManager?.callState
                                ?: TelephonyManager.CALL_STATE_IDLE
                        } catch (_: SecurityException) {
                            TelephonyManager.CALL_STATE_IDLE
                        }

                    CallStateMonitor.update(callState)

                    CallOverlayRepository.incomingCallVisible =
                        TelephonyStateRepository.activeCall

                    val state =
                        InterruptionCoordinator.evaluate(
                            batteryLevel,
                            charging,
                            0
                        )

                    InterruptionRepository.save(state)

                    val audioProtected =
                        AudioProtectionEngine.protect(
                            this@InterruptionAdapterService
                        )

                    val throttleMode =
                        CounterThrottleEngine.recommendedMode()

                    val attenuationLevel =
                        NotificationAttenuationEngine
                            .attenuationLevel()

                    val vibrationReduced =
                        NotificationAttenuationEngine
                            .vibrationAttenuated()

                    val soundReduced =
                        NotificationAttenuationEngine
                            .soundAttenuated()

                    AdapterHealthRegistry.update(
                        AdapterHealthSnapshot(
                            adapterName = "adapter_interruption",
                            status = state.severity,
                            lastHeartbeat = System.currentTimeMillis(),
                            errorCount = 0,
                            recoveryCount = 0,
                            details =
                                "battery=${state.batteryLevel}," +
                                "call=${TelephonyStateRepository.activeCall}," +
                                "audio=$audioProtected," +
                                "mode=$throttleMode," +
                                "attenuation=$attenuationLevel," +
                                "vibration=$vibrationReduced," +
                                "sound=$soundReduced"
                        )
                    )

                    RuntimeLogger.log(
                        "InterruptionAdapter heartbeat",
                        "HEALTH"
                    )

                } catch (e: Exception) {
                    RuntimeLogger.log(
                        "InterruptionAdapter heartbeat failed :: ${e.javaClass.simpleName}",
                        "HEALTH"
                    )
                } finally {
                    try {
                        interruptionHandler.postDelayed(
                            this,
                            10000
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        RuntimeLogger.log("InterruptionAdapterService started", "ADAPTER")

        workerThread =
            HandlerThread(
                "InterruptionWorker",
                Process.THREAD_PRIORITY_BACKGROUND
            )

        workerThread.start()

        interruptionHandler =
            Handler(workerThread.looper)

        val channel =
            NotificationChannel(
                "interruption_adapter",
                "Interruption Core",
                NotificationManager.IMPORTANCE_MIN
            )

        getSystemService(
            NotificationManager::class.java
        )?.createNotificationChannel(channel)

        startForeground(
            9998,
            Notification.Builder(
                this,
                "interruption_adapter"
            )
                .setContentTitle(
                    "Splendor Interruption Node"
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_info_details
                )
                .build()
        )

        interruptionHandler.post(
            interruptionRunnable
        )

        RuntimeLogger.log("Interruption heartbeat scheduler started", "HEALTH")
    }

    override fun onDestroy() {

        try {
            interruptionHandler.removeCallbacks(
                interruptionRunnable
            )
        } catch (_: Exception) {
        }

        RuntimeLogger.log("InterruptionAdapter heartbeat stopped", "HEALTH")

        try {
            workerThread.quitSafely()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
