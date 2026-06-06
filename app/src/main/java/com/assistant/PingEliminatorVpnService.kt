package com.assistant
import android.net.VpnService
import android.content.Intent

class PingEliminatorVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
