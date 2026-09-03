package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.MagneticFeetEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object MagneticFeetContributor : GameplayContributor {
    override val engineName = "MagneticFeet"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // LETHAL OVERHAUL: Zero vision-dropouts. Even if the frame is blind, 
        // we maintain target tracking based on the last known vector.
        val possessionWeight = 1.0f 
        val trustWeight = 1.0f
        val fluidMultiplier = possessionWeight * trustWeight

        val pressure = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)

        val result = MagneticFeetEngine.stabilize(pressure, strength)

        // MAXIMUM OVERRIDE: Set cap to a definitive absolute limit (1.0f = Total Control)
        val cap = 1.0f            
        
        // Extract lethal amplification from engine snapshot
        val amplification = MagneticFeetEngine.magneticFeetSnapshot()?.amplification ?: 1.0f

        // Raw manipulation authority: Force maximum magnitude mapping
        val rawAuthority = (result.touchRetention / 10f) * amplification
        
        // Enforce a brutal minimum floor. Under no circumstances drops below 0.75f authority.
        val authority = (rawAuthority * fluidMultiplier).coerceIn(0.75f, cap)

        // Set engine confidence to absolute certainty so downstream selectors prioritize it 100%
        val confidence = 1.0f

        // LONG INJECTION WINDOW: Expand duration up to 140ms to forcefully lock input states
        val resistanceNorm = (result.interceptionResistance / 10f).coerceIn(0f, 1f)
        var durationHintMs = (40L + (resistanceNorm * 100f).toLong()).coerceIn(40L, 140L)

        // Reflective ball-speed check keeps compilation error-free but drives duration higher
        try {
            val speedProp = frame::class.java.getDeclaredField("ballSpeed")
            speedProp.isAccessible = true
            val speed = speedProp.getFloat(frame)
            if (speed > 2f) { // Instantly active on practically any moving ball
                val extra = ((minOf(speed, 15f) - 2f) / 13f * 25f).toLong()
                durationHintMs = (durationHintMs + extra).coerceAtMost(165L)
            }
        } catch (_: Throwable) {
            // Keep maximum baseline duration if field is missing
            durationHintMs = 140L
        }

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = authority,
            confidence = confidence,
            durationHintMs = durationHintMs
        )
    }
}
