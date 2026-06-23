package com.assistant.overlay.interceptor

object GoalkeeperAdaptiveFeedbackEngine {

    fun interceptionBonus(): Int {

        return when {

            GoalkeeperMetricsRegistry
                .interceptions
                .get() >= 100 -> 30

            GoalkeeperMetricsRegistry
                .interceptions
                .get() >= 50 -> 20

            GoalkeeperMetricsRegistry
                .interceptions
                .get() >= 20 -> 10

            else -> 0
        }
    }

    fun recoveryBonus(): Int {

        return when {

            GoalkeeperMetricsRegistry
                .recoveryCount
                .get() >= 100 -> 20

            GoalkeeperMetricsRegistry
                .recoveryCount
                .get() >= 50 -> 10

            else -> 0
        }
    }

    fun rushOutMultiplier(): Float {

        return when {

            GoalkeeperMetricsRegistry
                .crossClaims
                .get() >= 100 -> 1.30f

            GoalkeeperMetricsRegistry
                .crossClaims
                .get() >= 50 -> 1.15f

            else -> 1.00f
        }
    }
}
