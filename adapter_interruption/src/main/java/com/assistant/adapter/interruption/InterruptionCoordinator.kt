package com.assistant.adapter.interruption

object InterruptionCoordinator {

    fun evaluate(
        batteryLevel: Int,
        charging: Boolean,
        thermalStatus: Int
    ): InterruptionState {

        val severity =
            when {
                thermalStatus >= 5 -> "CRITICAL"
                thermalStatus >= 3 -> "THROTTLE"
                batteryLevel < 15 && !charging -> "WARNING"
                else -> "NORMAL"
            }

        return InterruptionState(
            batteryLevel = batteryLevel,
            charging = charging,
            thermalStatus = thermalStatus,
            severity = severity,
            timestamp = System.currentTimeMillis()
        )
    }
}
