package com.assistant.recovery

object RecoveryMetricsRegistry {

    @Volatile
    private var recoveryAttempts = 0

    @Volatile
    private var successfulRecoveries = 0

    @Volatile
    private var offlineAdapters = 0

    @Volatile
    private var lastRecoveryTimestamp = 0L

    @Synchronized
    fun recordAttempt() {

        recoveryAttempts++

        lastRecoveryTimestamp =
            System.currentTimeMillis()
    }

    @Synchronized
    fun recordSuccess() {

        successfulRecoveries++

        lastRecoveryTimestamp =
            System.currentTimeMillis()
    }

    @Synchronized
    fun setOfflineAdapters(
        count: Int
    ) {
        offlineAdapters = count
    }

    @Synchronized
    fun recoveryRate(): Int {

        if (recoveryAttempts <= 0)
            return 100

        return (
            successfulRecoveries * 100
        ) / recoveryAttempts
    }

    @Synchronized
    fun snapshot(): String {

        return buildString {

            append(
                "Recovery Attempts : "
            )

            append(
                recoveryAttempts
            )

            append("\n")

            append(
                "Recoveries : "
            )

            append(
                successfulRecoveries
            )

            append("\n")

            append(
                "Offline Nodes : "
            )

            append(
                offlineAdapters
            )

            append("\n")

            append(
                "Recovery Rate : "
            )

            append(
                recoveryRate()
            )

            append("%")
        }
    }
}
