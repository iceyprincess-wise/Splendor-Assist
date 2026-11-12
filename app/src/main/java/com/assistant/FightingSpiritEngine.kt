package com.assistant






































import com.assistant.runtime.*
























import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*




























object FightingSpiritEngine {

    private const val MIN_ACCURACY_RETENTION = 1.0f

    private val activations = AtomicLong(0L)

    @Volatile
    private var lastActivationMs = 0L

    @Volatile
    private var lastRetention = MIN_ACCURACY_RETENTION

    @Volatile
    private var lastPressure = 0.0f

    @Volatile
    private var active = false

    fun reset() {
        activations.set(0L)
        lastActivationMs = 0L
        lastRetention = MIN_ACCURACY_RETENTION
        lastPressure = 0.0f
        active = false
    }
}

/* ========
FormationAdaptationEngine
======== */
