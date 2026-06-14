package com.assistant.adapter.interruption

object TelephonyLock {

    fun active(): Boolean {
        return TelephonyStateRepository.activeCall
    }
}
