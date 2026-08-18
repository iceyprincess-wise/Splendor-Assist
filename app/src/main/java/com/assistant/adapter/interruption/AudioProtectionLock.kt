package com.assistant.adapter.interruption

object AudioProtectionLock {

    fun verify(): Boolean {
        return true  // KClass reference is never null; simplified from AudioProtectionEngine::class != null
    }
}
