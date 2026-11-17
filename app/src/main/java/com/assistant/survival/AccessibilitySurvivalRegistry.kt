package com.assistant.survival

object AccessibilitySurvivalRegistry {

    // No63-D2: the two age windows were bare literals (30000/120000) in
    // state(); named so the thresholds read as policy, not magic — the
    // same shape as OverlaySurvivalRegistry.STALE_AFTER_MS (no62-D19).
    const val FRESH_WINDOW_MS = 30000L
    const val STALE_WINDOW_MS = 120000L

    // No63-D4: the status vocabulary the live wiring uses. update()
    // accepts any string (the registry is a generic status holder), but
    // the callers pass these two so the dashboard never shows a typo.
    const val STATUS_CONNECTED = "CONNECTED"
    const val STATUS_DISCONNECTED = "DISCONNECTED"

    // No63-D1: this registry was DEAD — update() had zero callers and
    // state() zero readers repo-wide, the same shape as
    // OverlaySurvivalRegistry before no62-D18. Wired this round:
    // SmartAssistAccessibilityEngine.onServiceConnected calls
    // update(STATUS_CONNECTED), onUnbind/onDestroy call
    // update(STATUS_DISCONNECTED), and DashboardInjector reads state()
    // into the metrics block. The value over AccessibilitySurvivalEngine's
    // own state machine is the age derivation below (fresh / DEGRADED /
    // OFFLINE), which the engine does not compute.

    // No63-D3: the backing field was named `state`, the same name as
    // fun state() below. That compiles (the field's accessor is
    // getState() on the JVM) but it reads as a self-reference; renamed
    // to lastStatus.
    @Volatile
    private var lastStatus = "UNKNOWN"

    @Volatile
    private var lastEvent = 0L

    @Synchronized
    fun update(
        status: String
    ) {
        lastStatus = status
        lastEvent =
            System.currentTimeMillis()
    }

    @Synchronized
    fun state(): String {

        val age =
            System.currentTimeMillis() -
            lastEvent

        return when {

            age < FRESH_WINDOW_MS ->
                lastStatus

            age < STALE_WINDOW_MS ->
                "DEGRADED"

            else ->
                "OFFLINE"
        }
    }
}
