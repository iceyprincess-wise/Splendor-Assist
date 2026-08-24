package com.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import com.assistant.controlroom.ui.SmartAssistControlRoomActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

class PingEliminatorVpnService : VpnService() {

    companion object {
        private const val THREAD_NAME_TUNNEL = "SplendorTunnelWorker"
        private const val DEFAULT_MTU = 1500
        private const val BUFFER_SIZE = 16384
        private const val CHANNEL_ID = "SplendorVpnChannel"
        
        private const val MIN_JITTER_MS = 2L
        private const val MAX_JITTER_MS = 8L
    }

    private val isRunning = AtomicBoolean(false)
    private var vpnInterface: ParcelFileDescriptor? = null

    private var tunnelThread: HandlerThread? = null
    private var tunnelHandler: Handler? = null

    private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>()

    override fun onCreate() {
        super.onCreate()
        preallocateBufferPool()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning.get()) {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notification)
            }
            startVpnEngine()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpnEngine()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Splendor Assist Network",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Maintains secure connection"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, SmartAssistControlRoomActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Splendor Assist Active")
            .setContentText("Network latency masking enabled")
            .setSmallIcon(android.R.drawable.ic_dialog_info) 
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun preallocateBufferPool() {
        for (i in 0 until 64) {
            bufferPool.offer(ByteBuffer.allocateDirect(BUFFER_SIZE))
        }
    }

    private fun obtainBuffer(): ByteBuffer {
        val buf = bufferPool.poll() ?: ByteBuffer.allocateDirect(BUFFER_SIZE)
        buf.clear()
        return buf
    }

    private fun releaseBuffer(buf: ByteBuffer) {
        if (bufferPool.size < 128) {
            buf.clear()
            bufferPool.offer(buf)
        }
    }

    private fun startVpnEngine() {
        if (!isRunning.compareAndSet(false, true)) return

        val dummyIntent = Intent()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val configureIntent = PendingIntent.getBroadcast(this, 0, dummyIntent, flags)

        val builder = Builder()
            .setMtu(DEFAULT_MTU)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setSession("SplendorPossessionEngine")
            .setConfigureIntent(configureIntent)
            .addDnsServer("8.8.8.8") 

        try {
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            isRunning.set(false)
            return
        }

        val pfd = vpnInterface ?: return

        tunnelThread = HandlerThread(THREAD_NAME_TUNNEL, android.os.Process.THREAD_PRIORITY_BACKGROUND)
        tunnelThread?.start()
        tunnelHandler = Handler(tunnelThread?.looper ?: android.os.Looper.getMainLooper())

        tunnelHandler?.post {
            executeLowLevelIO(pfd)
        }
    }

    private fun executeLowLevelIO(pfd: ParcelFileDescriptor) {
        val fis = FileInputStream(pfd.fileDescriptor)
        val fos = FileOutputStream(pfd.fileDescriptor)
        val inChannel = fis.channel
        val outChannel = fos.channel

        val readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        val random = ThreadLocalRandom.current()

        while (isRunning.get()) {
            try {
                readBuffer.clear()
                val bytesRead = inChannel.read(readBuffer)
                if (bytesRead > 0) {
                    readBuffer.flip()
                    
                    val payload = obtainBuffer()
                    if (payload.remaining() >= readBuffer.remaining()) {
                        payload.put(readBuffer)
                        payload.flip()
                        
                        val jitterMs = random.nextLong(MIN_JITTER_MS, MAX_JITTER_MS + 1)
                        if (jitterMs > 0) {
                            Thread.sleep(jitterMs)
                        }
                        
                        outChannel.write(payload)
                        releaseBuffer(payload)
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
        
        try {
            inChannel.close()
            outChannel.close()
            fis.close()
            fos.close()
        } catch (ignored: Exception) {}
    }

    private fun stopVpnEngine() {
        if (!isRunning.compareAndSet(true, false)) return

        try {
            vpnInterface?.close()
        } catch (ignored: Exception) {
        } finally {
            vpnInterface = null
        }

        tunnelThread?.quitSafely()
        tunnelThread = null
        tunnelHandler = null

        bufferPool.clear()
    }
}
