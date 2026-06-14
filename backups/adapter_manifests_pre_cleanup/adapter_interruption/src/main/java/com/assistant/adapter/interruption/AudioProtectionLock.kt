package com.assistant.adapter.interruption

object AudioProtectionLock {

    fun verify(): Boolean {
        return AudioProtectionEngine::class != null
    }
}
