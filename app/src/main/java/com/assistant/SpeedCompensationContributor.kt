package com.assistant

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.*
import kotlin.math.atan2

object SpeedCompensationContributor : GameplayContributor {
    override val engineName = "SpeedCompensation"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null

        // distance: proxy from defenderDensity — high density = close threats
        val distance = ((1f - frame.defenderDensity) * 800f).coerceIn(0f, 1000f)
        // strength: how much pressure — scaled to 0..100
        val strength = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        // angle: derive from ball-to-pass-target vector; 0 when no lane
        val angle = if (frame.passTargetX > 0f || frame.passTargetY > 0f) {
            val dx = frame.passTargetX - frame.ballX
            val dy = frame.passTargetY - frame.ballY
            Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        } else 0f

        val result = SpeedCompensationEngine.compensate(distance, angle, strength)

        // authority from executionBoost — max is 35.0 in engine
        val authority = (result.executionBoost / 35f).coerceIn(0f, 1f)
        if (authority <= 0f) return null

        // Target: toward pass lane when open, toward ball when defending
        val targetX = if (frame.hasBall && frame.passTargetX > 0f)
            frame.passTargetX else frame.ballX.coerceAtLeast(0f)
        val targetY = if (frame.hasBall && frame.passTargetY > 0f)
            frame.passTargetY else frame.ballY.coerceAtLeast(0f)

        return EngineContribution(
            engine = engineName,
            actionClass = if (frame.hasBall) ActionClass.MOVE else ActionClass.DEFEND,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = run {
                // PHASE3: thermal + battery now properly wired into duration scaling
                // SPLENDOR_P7_AGGRO_READ_SPEED_BEGIN
                // Task 2C: read the cross-process registry value, the same
                // channel every other aggression consumer uses.
                val aggro = com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.currentFilterAggression()
                // SPLENDOR_P7_AGGRO_READ_SPEED_END
                // Bus snapshot() rewiring (batch 5): one snapshot, derived
                // once — the old code read nine separate bus getters, so
                // the `when` branches could mix generations (e.g. a
                // thermal value from before a publish with a battery value
                // from after it). Formulas are the getters' own
                // (AdapterSignalBus), evaluated on this one generation.
                val snap = AdapterSignalBus.snapshot()
                val thermalIsSevere = snap.thermalStatus >= 3
                val batteryCritical = snap.batteryLevel in 0..14 && !snap.batteryCharging
                val lagIsChoking = snap.lagVerdict == "CHOKING"
                val memoryIsCritical = snap.memoryTier == "CRITICAL"
                val inputIsLagging = snap.inputClassification == "LAGGING" || snap.inputClassification == "TOUCH_IRQ_STALL"
                val stutterIsSevere = snap.stutterState == "SEIZURE"
                val memoryIsUnderPressure = snap.memoryTier == "CRITICAL" || snap.memoryTier == "PRESSURE"
                val executionIsFullyBraked = snap.executionBrake >= 2
                val executionIsBraked = snap.executionBrake > 0
                val p = when {
                    thermalIsSevere -> 0.4f          // severe heat: very short gestures
                    batteryCritical -> 0.5f          // critical battery: reduce load
                    lagIsChoking || memoryIsCritical -> 0.5f
                    inputIsLagging -> 0.7f
                    stutterIsSevere -> 0.65f         // now actually works (bus was never published before)
                    snap.lagVerdict == "JITTERY" || memoryIsUnderPressure -> 0.8f
                    // Live adaptive brake: PerformanceEngineService publishes
                    // executionBrake from measured load shedding, but nothing on
                    // the execution path read it, so the brake was a decorative
                    // flag. Honour it here by shortening the gesture window.
                    executionIsFullyBraked -> 0.55f
                    executionIsBraked -> 0.8f
                    aggro > 1.5f -> 1.2f // EXTREMIST OVERRIDE: Force longer holds for guaranteed execution
                    else -> 1.0f
                }
                (30L * p).toLong().coerceIn(12L, if (aggro > 1.5f) 90L else 60L)
            }
        )
    }
}
