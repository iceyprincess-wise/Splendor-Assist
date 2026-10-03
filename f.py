#!/usr/bin/env python3
"""
f9.py — Surgical conversion of Agility and BallRetention to pure JNI tiny adapters.
================================================================================
Targets the EXACT current state of SHA 08630951.
"""
import os

PATCHES = [
    # 1. AgilityContributor: Strip all Kotlin math/Phase3WorldStateStore logic, replace with pure JNI
    (
        "app/src/main/java/com/assistant/gameplay_engine.kt",
        [
            (
                """object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // UNCONDITIONAL: Support positioning must happen even without the ball
        if (!frame.trusted) return null

        var oppX: Float? = null
        var oppY: Float? = null
        var opponentDistance = (1f - frame.defenderDensity) * 300f

        try {
            val def = Phase3WorldStateStore.current().defender
            if (def.found && def.distanceToAttacker < Float.MAX_VALUE) {
                opponentDistance = def.distanceToAttacker.coerceIn(0f, 1200f)
                val trackedDef = def.defender
                if (trackedDef != null) {
                    oppX = trackedDef.x
                    oppY = trackedDef.y
                }
            }
        } catch (_: Throwable) {
            // Safe store extraction fallback
        }

        val ballX = frame.ballX
        val ballY = frame.ballY

        val movementAngle = if (frame.passTargetX > 0f || frame.passTargetY > 0f) {
            val dx = (frame.passTargetX - ballX).toDouble()
            val dy = (frame.passTargetY - ballY).toDouble()
            Math.toDegrees(atan2(dy, dx)).toFloat()
        } else {
            0f
        }

        val estimatedVelocity = (frame.bestLaneConfidence * 10.0f + frame.confidence * 5.0f).coerceIn(0f, 15f)
        val turnIntensity = frame.defenderDensity.coerceIn(0f, 1f)

        val agilityBuffer = FloatArray(6)
        NativeBridge.nativeAgilityPhysics(
            estimatedVelocity, opponentDistance, movementAngle,
            frame.confidence, turnIntensity, ballX, ballY, oppX ?: Float.NaN, oppY ?: Float.NaN, NativeBridge.nextThreadSeed(), agilityBuffer
        )
        val result = AgilityResult(
            shieldActive = agilityBuffer[5] > 0.5f,
            stabilityBoost = agilityBuffer[0],
            controlRetentionBoost = agilityBuffer[1],
            turnAssist = agilityBuffer[2],
            shieldAngleDegrees = agilityBuffer[3],
            shieldDurationMs = agilityBuffer[4].toLong()
        )

        val baseAuthority = (result.stabilityBoost / 10f) + (result.controlRetentionBoost * 0.2f)
        val authority = baseAuthority.coerceIn(0.40f, 1.0f)

        val targetX: Float
        val targetY: Float

        if (frame.viableLaneCount > 0 && frame.passTargetX > 0f) {
            targetX = frame.passTargetX
            targetY = if (frame.passTargetY > 0f) frame.passTargetY else ballY
        } else {
            if (oppX != null && oppY != null && opponentDistance < 250f) {
                val shieldRad = Math.toRadians(result.shieldAngleDegrees.toDouble())
                val pushDist = 75f + (result.turnAssist * 45f)
                targetX = (ballX + cos(shieldRad).toFloat() * pushDist).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
                targetY = (ballY + sin(shieldRad).toFloat() * pushDist).coerceIn(0f, 1080f)
            } else {
                val yOffset = if (result.turnAssist > 0.2f) {
                    if (ballY > 540f) -40f else 40f
                } else {
                    0f
                }
                targetX = (ballX + 85f).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
                targetY = (ballY + yOffset).coerceIn(0f, 1080f)
            }
        }

        val duration = result.shieldDurationMs.coerceIn(16L, 100L)

        return EngineContribution(
            engineName,
            ActionClass.MOVE,
            targetX,
            targetY,
            authority,
            frame.confidence,
            duration
        )
    }
}""",
                """object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)
    
    // ZERO-ALLOCATION JNI BUFFER
    private val nativeAgilityOut = FloatArray(4) // [targetX, targetY, authority, confidence]

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // UNCONDITIONAL AGGRESSIVE EXECUTION: Removed all Kotlin math/state extraction
        
        // JNI TINY ADAPTER: Delegate heavy physics to native C ABI
        val success = com.assistant.NativeBridge.nativeComputeAgilityPhysics(
            frame.ballX, frame.ballY,
            frame.passTargetX, frame.passTargetY,
            frame.defenderDensity,
            nativeAgilityOut
        )
        
        if (!success || nativeAgilityOut[2] < 0.1f) return null // Native layer determined no viable agile move
        
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = nativeAgilityOut[0].coerceAtLeast(0f),
            targetY = nativeAgilityOut[1].coerceAtLeast(0f),
            authority = nativeAgilityOut[2],
            confidence = nativeAgilityOut[3],
            durationHintMs = 20L // Ultra-fast transition
        )
    }
}"""
            )
        ]
    ),
    # 2. BallRetentionShieldContributor: Clean up to pure tiny adapter
    (
        "app/src/main/java/com/assistant/gameplay_engine.kt",
        [
            (
                """object BallRetentionShieldContributor : GameplayContributor {
    override val engineName   = "BallRetentionShield"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)
    
    // Pre-allocated buffer for zero-alloc JNI crossing
    private val nativeBuffer = FloatArray(5)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        com.assistant.NativeBridge.nativeBallRetentionShieldCompute(
            frame.hasBall,
            frame.trusted,
            frame.confidence,
            frame.ballX,
            frame.ballY,
            frame.viableLaneCount,
            frame.passTargetX,
            frame.passTargetY,
            frame.zones.leftTheirs,
            frame.zones.midTheirs,
            frame.zones.rightTheirs,
            frame.defenderDensity,
            frame.laneCount,
            nativeBuffer
        )

        if (nativeBuffer[0] <= 0.5f) return null
        
        return EngineContribution(engineName, ActionClass.MOVE,
            nativeBuffer[1], nativeBuffer[2], nativeBuffer[3], frame.confidence, 24L)
    }
}""",
                """object BallRetentionShieldContributor : GameplayContributor {
    override val engineName   = "BallRetentionShield"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)
    
    // ZERO-ALLOCATION JNI BUFFER
    private val nativeBuffer = FloatArray(4) // [targetX, targetY, authority, confidence]

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // UNCONDITIONAL AGGRESSIVE EXECUTION: Always evaluate shield positioning
        
        // JNI TINY ADAPTER: Direct C ABI call, zero JVM allocation overhead
        val success = com.assistant.NativeBridge.nativeComputeBallRetention(
            frame.ballX, frame.ballY,
            frame.defenderDensity,
            nativeBuffer
        )
        
        if (!success || nativeBuffer[2] < 0.1f) return null 
        
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = nativeBuffer[0].coerceAtLeast(0f),
            targetY = nativeBuffer[1].coerceAtLeast(0f),
            authority = nativeBuffer[2],
            confidence = nativeBuffer[3],
            durationHintMs = 15L // Maximum reaction speed
        )
    }
}"""
            )
        ]
    )
]

def apply_patch(path: str, replacements):
    if not os.path.exists(path):
        print(f"  ❌ SKIP: {path} (not found)")
        return
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    
    modified = False
    for old, new in replacements:
        if old in src:
            src = src.replace(old, new, 1)
            print(f"  ✅ PATCHED: {path}")
            modified = True
        elif new in src:
            print(f"  ⏭️  ALREADY: {path}")
        else:
            print(f"  ⚠️  WARN: Anchor not found in {path}")
            
    if modified:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)

def main():
    for path, reps in PATCHES:
        apply_patch(path, reps)
    print("\n✅ f9.py execution complete.")

if __name__ == "__main__":
    main()
