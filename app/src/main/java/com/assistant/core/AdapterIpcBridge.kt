package com.assistant.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log

object AdapterIpcBridge {
    private var lmkMessenger: Messenger? = null
    private var syncMessenger: Messenger? = null
    private var inputMessenger: Messenger? = null
    private var netMessenger: Messenger? = null

    // LMK Connection
    private val lmkConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            lmkMessenger = Messenger(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) { lmkMessenger = null }
    }

    // Sync Connection
    private val syncConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            syncMessenger = Messenger(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) { syncMessenger = null }
    }

    // Input Connection
    private val inputConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            inputMessenger = Messenger(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) { inputMessenger = null }
    }

    // Net Connection
    private val netConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            netMessenger = Messenger(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) { netMessenger = null }
    }

    fun bindAllAdapters(context: Context) {
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        
        context.bindService(Intent().setComponent(ComponentName("com.assistant.adapter.lmk", "com.assistant.adapter.lmk.LmkAdapterService")), lmkConnection, flags)
        context.bindService(Intent().setComponent(ComponentName("com.assistant.adapter.sync", "com.assistant.adapter.sync.SyncAdapterService")), syncConnection, flags)
        context.bindService(Intent().setComponent(ComponentName("com.assistant.adapter.input", "com.assistant.adapter.input.InputAdapterService")), inputConnection, flags)
        context.bindService(Intent().setComponent(ComponentName("com.assistant.adapter.net", "com.assistant.adapter.net.NetAdapterService")), netConnection, flags)
    }

    // --- EXECUTION TRIGGERS ---

    // A. LMK Trigger: Lock Thread Priority to URGENT_DISPLAY during active match
    fun triggerLmkMatchStateLock() {
        lmkMessenger?.send(Message.obtain(null, 101))
    }

    // B. Sync Trigger: Dispatch Frame Timing
    fun dispatchSyncPulse(frameTimeNanos: Long) {
        val msg = Message.obtain(null, 201)
        msg.data.putLong("frame_time", frameTimeNanos)
        syncMessenger?.send(msg)
    }

    // C. Input Trigger: Route Raw Touch Matrix
    fun dispatchRawTouchMatrix(x: Float, y: Float, action: Int) {
        val msg = Message.obtain(null, 301)
        msg.data.putFloat("x", x)
        msg.data.putFloat("y", y)
        msg.data.putInt("action", action)
        inputMessenger?.send(msg)
    }

    // D. Net Trigger: Push Traffic Shaping Flag
    fun enforceTrafficShapingProtocol(isGameActive: Boolean) {
        val msg = Message.obtain(null, 401)
        msg.arg1 = if (isGameActive) 1 else 0
        netMessenger?.send(msg)
    }
}
