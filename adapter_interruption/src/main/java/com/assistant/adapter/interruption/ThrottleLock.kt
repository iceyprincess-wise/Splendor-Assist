package com.assistant.adapter.interruption

object ThrottleLock {

    fun currentMode(): String {
        return CounterThrottleEngine.recommendedMode()
    }
}
