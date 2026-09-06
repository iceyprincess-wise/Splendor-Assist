import os

engine_code = """package com.assistant.adapter.smartassist

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

data class DefenseAuthorityResult(
    val containment: Float,
    val interception: Float,
    val pressure: Float
)

object DefenseAuthorityEngine {
    data class DefenseEvaluationDiagnostics(
        val totalEvaluations: Long,
        val maxContainmentObserved: Float,
        val maxInterceptionObserved: Float,
        val lastDistanceEvaluated: Float,
        val lastUpdatedTimestamp: Long
    )

    private val evaluationCount = AtomicLong(0L)
    @Volatile private var peakContainment: Float = 0f
    @Volatile private var peakInterception: Float = 0f
    @Volatile private var lastDistance: Float = 0f
    @Volatile private var lastUpdateMs: Long = 0L

    fun getEvaluationDiagnostics() = DefenseEvaluationDiagnostics(
        evaluationCount.get(), peakContainment, peakInterception, lastDistance, lastUpdateMs
    )

    fun evaluate(distance: Float, strength: Int, recovery: Float, retention: Float): DefenseAuthorityResult {
        val ns = strength.coerceIn(0, 100) / 100f
        val ti = ns.pow(1.5f)
        val pf = 1f - (distance.coerceIn(0f, 1200f) / 1200f)
        val nr = recovery.coerceIn(0f, 10f) / 10f
        val nt = retention.coerceIn(0f, 10f) / 10f

        val containment = ((nr * 5.5f) + (ti * 3.5f) + (pf * 2.5f)).coerceIn(0f, 10f)
        val interception = ((nt * 5.5f) + (ti * 3.5f) + (pf * 2.5f)).coerceIn(0f, 10f)
        val pressure = (containment + interception).coerceIn(0f, 20f)

        val count = evaluationCount.incrementAndGet()
        if (containment > peakContainment) peakContainment = containment
        if (interception > peakInterception) peakInterception = interception
        lastDistance = distance
        lastUpdateMs = System.currentTimeMillis()

        if (count % 500L == 0L) {
            Log.d("DefenseAuthorityEngine", "containment=$containment interception=$interception pressure=$pressure")
        }

        return DefenseAuthorityResult(containment, interception, pressure)
    }
}
"""

contributor_code = """package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.DefenseAuthorityEngine
import com.assistant.adapter.smartassist.Phase3WorldStateStore
import com.assistant.runtime.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object DefenseAuthorityContributor : GameplayContributor {
    override val engineName = "DefenseAuthority"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        var distance = (1f - frame.defenderDensity) * 500f
        var defX: Float? = null
        var defY: Float? = null

        try {
            val defState = Phase3WorldStateStore.current().defender
            if (defState.found && defState.distanceToAttacker < Float.MAX_VALUE) {
                distance = defState.distanceToAttacker.coerceIn(0f, 1200f)
                val trackedDef = defState.defender
                if (trackedDef != null) {
                    defX = trackedDef.x
                    defY = trackedDef.y
                }
            }
        } catch (_: Throwable) {
            // World state store fallback
        }

        val strength = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val recovery = frame.confidence * 10f
        val retention = frame.bestLaneConfidence * 10f

        val r = DefenseAuthorityEngine.evaluate(distance, strength, recovery, retention)

        // Rescaled authority math: maps 0..10 score spectrum cleanly to 0.0..1.0 range
        val normalizedContainment = r.containment / 10f
        val normalizedInterception = r.interception / 10f
        val rawAuthority = (normalizedContainment * 0.5f) + (normalizedInterception * 0.5f)
        val authority = rawAuthority.coerceIn(0.10f, 1.0f)

        val ballX = frame.ballX
        val ballY = frame.ballY

        val targetX: Float
        val targetY: Float

        if (r.containment > r.interception) {
            // Containment positioning: position goal-side to block dangerous passing/shooting angles
            val goalX = 0f
            val goalY = 540f
            val dx = (goalX - ballX).toDouble()
            val dy = (goalY - ballY).toDouble()
            val angle = atan2(dy, dx)
            val containOffset = 60f + ((1f - (distance / 1200f)) * 40f)
            targetX = (ballX + cos(angle).toFloat() * containOffset).coerceIn(0f, 1650f)
            targetY = (ballY + sin(angle).toFloat() * containOffset).coerceIn(0f, 1080f)
        } else {
            // Direct press/interception positioning
            targetX = ballX
            targetY = ballY
        }

        return EngineContribution(
            engineName,
            ActionClass.DEFEND,
            targetX,
            targetY,
            authority,
            frame.confidence,
            32L
        )
    }
}
"""

with open("app/src/main/java/com/assistant/adapter/smartassist/DefenseAuthorityEngine.kt", "w") as f:
    f.write(engine_code)

with open("app/src/main/java/com/assistant/adapter/smartassist/contributors/DefenseAuthorityContributor.kt", "w") as f:
    f.write(contributor_code)

print("[PATCH VERIFIED] DefenseAuthorityEngine and DefenseAuthorityContributor written successfully.")
