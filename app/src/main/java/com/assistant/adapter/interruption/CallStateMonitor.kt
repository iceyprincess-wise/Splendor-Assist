package com.assistant.adapter.interruption

import android.telephony.TelephonyManager

object CallStateMonitor {

    fun update(state: Int) {
        TelephonyStateRepository.activeCall =
            state != TelephonyManager.CALL_STATE_IDLE
    }
}
