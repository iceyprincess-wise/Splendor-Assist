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
        // No63-D7: a negative count is meaningless (offline is a count of
        // adapters); clamp at the write site so the stored state can never
        // hold garbage the dashboard would print.
        offlineAdapters = count.coerceAtLeast(0)
    }

    @Synchronized
    fun recoveryRate(): Int {

        // No63-D6: returning 100 with zero attempts was a fabricated 100%
        // on a fresh dashboard. F37-T3 papered over it in snapshot()'s
        // render ("n/a"), but the function itself still lied to any other
        // caller. The rate of zero attempts is 0; snapshot() still
        // renders "n/a" for the zero-attempt case (its branch below is
        // unchanged).
        if (recoveryAttempts <= 0)
            return 0

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

            // F37-T3: recoveryRate() returns 100 with zero attempts — a
            // fabricated 100% on a fresh dashboard. Render "n/a" instead.
            append(
                if (recoveryAttempts <= 0) "n/a" else "${recoveryRate()}%"
            )

            append("\n")

            append(
                "Last Recovery : "
            )

            // No63-D5: lastRecoveryTimestamp was write-only — recorded on
            // every attempt/success but never read by anyone. Rendered
            // here so DashboardInjector (the live snapshot() reader)
            // shows it: "never" on a fresh registry, seconds-since
            // otherwise.
            append(
                if (lastRecoveryTimestamp <= 0L) "never"
                else "${(System.currentTimeMillis() - lastRecoveryTimestamp) / 1000}s ago"
            )
        }
    }
}
