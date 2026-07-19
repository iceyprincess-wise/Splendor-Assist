package com.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Choreographer
import android.view.Display
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin
import kotlin.random.Random

class PingEliminatorVpnService : VpnService(), Choreographer.FrameCallback {

    companion object {
        private const val THREAD_NAME_TUNNEL = "SplendorTunnelWorker"
        private const val THREAD_NAME_ENGINE = "SplendorEngineWorker"
        private const val DEFAULT_MTU = 1500
        private const val BUFFER_SIZE = 16384
        private const val BASE_HUMAN_LATENCY_MS = 8L
    }

    private val isRunning = AtomicBoolean(false)
    private var vpnInterface: ParcelFileDescriptor? = null

    private var tunnelThread: HandlerThread? = null
    private var tunnelHandler: Handler? = null

    private var engineThread: HandlerThread? = null
    private var engineHandler: Handler? = null

    // High-frequency thread-safe memory buffers
    private val inboundQueue = ConcurrentLinkedQueue<ByteBuffer>()
    private val outboundQueue = ConcurrentLinkedQueue<ByteBuffer>()
    private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>()

    // Hardware frame-rate profile tracking
    private var targetFrameRateHz = 60.0f
    private var frameIntervalNs = 16666666L

    override fun onCreate() {
        super.onCreate()
        detectHardwareRefreshRate()
        preallocateBufferPool()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning.get()) {
            startVpnEngine()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpnEngine()
        super.onDestroy()
    }

    private fun detectHardwareRefreshRate() {
        try {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            val refreshRate = display?.refreshRate ?: 60.0f

            if (refreshRate > 0.0f) {
                targetFrameRateHz = refreshRate
                frameIntervalNs = (1000000000.0f / targetFrameRateHz).toLong()
            }
        } catch (e: Exception) {
            targetFrameRateHz = 60.0f
            frameIntervalNs = 16666666L
        }
    }

    private fun preallocateBufferPool() {
        for (i in 0 until 128) {
            bufferPool.offer(ByteBuffer.allocateDirect(BUFFER_SIZE))
        }
    }

    private fun obtainBuffer(): ByteBuffer {
        val buf = bufferPool.poll() ?: ByteBuffer.allocateDirect(BUFFER_SIZE)
        buf.clear()
        return buf
    }

    private fun releaseBuffer(buf: ByteBuffer) {
        if (bufferPool.size < 256) {
            buf.clear()
            bufferPool.offer(buf)
        }
    }

    private fun startVpnEngine() {
        if (!isRunning.compareAndSet(false, true)) return

        // Resolve PendingIntent non-null typing constraint for modern Gradle compilers
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

        try {
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            isRunning.set(false)
            return
        }

        val pfd = vpnInterface ?: return

        tunnelThread = HandlerThread(THREAD_NAME_TUNNEL, android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        tunnelThread?.start()
        tunnelHandler = Handler(tunnelThread?.looper ?: Looper.getMainLooper())

        engineThread = HandlerThread(THREAD_NAME_ENGINE, android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        engineThread?.start()
        engineHandler = Handler(engineThread?.looper ?: Looper.getMainLooper())

        tunnelHandler?.post {
            executeLowLevelIO(pfd)
        }

        // Initialize high-precision VSYNC Choreographer framework loop
        engineHandler?.post {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning.get()) return

        processSyncTick()

        // Chain the framework callback to track hardware refresh rate transitions perfectly
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun executeLowLevelIO(pfd: ParcelFileDescriptor) {
        val fis = FileInputStream(pfd.fileDescriptor)
        val fos = FileOutputStream(pfd.fileDescriptor)
        val inChannel = fis.channel
        val outChannel = fos.channel

        val readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)

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
                        outboundQueue.offer(payload)
                    }
                }

                while (!inboundQueue.isEmpty()) {
                    val writeBuffer = inboundQueue.poll()
                    if (writeBuffer != null) {
                        outChannel.write(writeBuffer)
                        releaseBuffer(writeBuffer)
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

    private fun processSyncTick() {
        while (!outboundQueue.isEmpty()) {
            val packet = outboundQueue.poll() ?: break

            val systemTimeNanos = System.nanoTime()
            val noiseOffset = sin(systemTimeNanos.toDouble()).coerceIn(-1.0, 1.0)
            val randomJitter = Random.nextLong(1, BASE_HUMAN_LATENCY_MS + 1)

            val finalizedDelay = (noiseOffset * randomJitter).toLong()

            if (finalizedDelay > 0) {
                try {
                    Thread.sleep(finalizedDelay.coerceAtMost(BASE_HUMAN_LATENCY_MS))
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            inboundQueue.offer(packet)
        }
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
        engineThread?.quitSafely()

        tunnelThread = null
        tunnelHandler = null
        engineThread = null
        engineHandler = null

        // Safe cleanup of pooled buffers to completely eliminate memory allocation leaks
        var queuedInbound = inboundQueue.poll()
        while (queuedInbound != null) {
            releaseBuffer(queuedInbound)
            queuedInbound = inboundQueue.poll()
        }

        var queuedOutbound = outboundQueue.poll()
        while (queuedOutbound != null) {
            releaseBuffer(queuedOutbound)
            queuedOutbound = outboundQueue.poll()
        }

        inboundQueue.clear()
        outboundQueue.clear()
        bufferPool.clear()
    }
}
