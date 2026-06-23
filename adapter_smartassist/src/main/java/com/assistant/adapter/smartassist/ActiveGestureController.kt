package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.FrameDropStabilizer
import com.assistant.adapter.smartassist.fps.MemoryStabilityOptimizer
import com.assistant.adapter.smartassist.fps.VsyncInputAnchor
import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.atan2

class ActiveGestureController(
    private val service: AccessibilityService
) {

    private val memoryOptimizer = MemoryStabilityOptimizer(service)

    private val vsyncAnchor = VsyncInputAnchor { _ -> RuntimeLogger.log("VSync dispatch synchronized","SMART_ASSIST") }

    private val latencyInputEngine = LatencyDefeatingInputEngine(service)


    fun injectWinningVector(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ) {

        if (!SmartAssistRepository.enabled()) {
            RuntimeLogger.log("SmartAssist disabled, ignoring vector", "SMART_ASSIST")
            return
        }

        val deltaX = endX - startX
        val deltaY = endY - startY
        val distance = kotlin.math.hypot(deltaX, deltaY)

        val angle = Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()

        val mode = when {
            distance < 100f -> 0
            abs(angle) <= 45f || abs(angle) >= 135f -> 1
            abs(angle) in 60f..120f -> 2
            else -> 0
        }

        val strength = when (mode) {
            1 -> SmartAssistRepository.configuration().passThreshold
            2 -> SmartAssistRepository.configuration().shotThreshold
            else -> SmartAssistRepository.configuration().crossThreshold
        }

        val compensation =
            HybridResponseCompensationEngine.compensate(
                startX,
                startY,
                endX,
                endY,
                duration,
                strength
            )

        val predictiveX = compensation.endX
        val predictiveY = compensation.endY

        val speedComp =
            SpeedCompensationEngine.compensate(
                distance,
                angle,
                strength
            )

        val touchRecovery =
            TouchRecoveryEngine.recover(
                (distance.toInt().coerceIn(0,100)),
                strength
            )

        val magneticFeet =
            MagneticFeetEngine.stabilize(
                (distance.toInt().coerceIn(0,100)),
                strength
            )

        val shieldAngle =
            ShieldAssistEngine.shieldAngle(
                angle
            )

        val passAssist =
            TrueTargetPassingEngine.optimize(
                startX,
                startY,
                predictiveX,
                predictiveY,
                magneticFeet.touchRetention
            )

        val crossAssist =
            CrossPrecisionEngine.calculate(
                passAssist.correctedX,
                passAssist.correctedY,
                strength
            )

        val receiverEngagement =
            ReceiverEngagementEngine.evaluate(
                distance,
                magneticFeet.touchRetention
            )

        val forwardRun =
            ForwardRunOpportunityEngine.evaluate(
                distance,
                strength
            )

        val shotAnalysis =
            ShotOpportunityAnalysisEngine.analyze(
                distance,
                0.25f
            )

        val boostedDuration =
            FrameDropCompensationEngine.compensate(
                (
                    duration /
                    speedComp.executionBoost
                ).toLong().coerceAtLeast(15L),
                strength
            )


        val inputDiagnostics =
            InputAccumulationDiagnosticsEngine.analyze(
                boostedDuration
            )

        val finalX =
            crossAssist.crossX +
            (shieldAngle * 0.02f * touchRecovery.balanceStrength) +
            (receiverEngagement.engagementBoost * 2f) +
            (shotAnalysis.openSideScore * 3f)

        val finalY =
            crossAssist.crossY +
            (touchRecovery.recoveryBoost * 2f) +
            (forwardRun.runBoost * 2f) +
            (inputDiagnostics.stabilityScore * 2f)

        SmartAssistPipeline().computeOptimalVector(
            startX,
            startY,
            finalX,
            finalY,
            boostedDuration
        )

        if (duration <= 50L && SmartAssistRepository.panicActive()) {
            SmartAssistRepository.activatePanic()
        } else {
            SmartAssistRepository.clearPanic()
        }

        memoryOptimizer.initialize();

        val frameDropMonitor = FrameDropStabilizer();
        frameDropMonitor.start {}

        FalseRunSequenceEngine.injectFalseRunSequence(
            latencyInputEngine,
            startX,
            startY,
            deltaX,
            deltaY
        )

        SmartAssistMetrics.submitRequest()

        CentralExecutionBus.submit(
            ExecutionRequest(
                source = ExecutionSource.SMART_ASSIST,
                phase = mode,
                startX = startX,
                startY = startY,
                endX = finalX,
                endY = finalY,
                duration = boostedDuration
            )
        )

        RuntimeLogger.log(
            "SmartAssist submitted mode=$mode distance=${distance.toInt()} strength=$strength panic=${SmartAssistRepository.panicActive()}",
            "SMART_ASSIST"
        )
    }

    fun emergencyClearanceVector(
        startX: Float,
        startY: Float,
        targetY: Float
    ): Pair<Float, Float> {

        return Pair(
            startX,
            targetY
        )
    }

    fun emergencyClearanceDuration(): Long {
        return 140L
    }

}
