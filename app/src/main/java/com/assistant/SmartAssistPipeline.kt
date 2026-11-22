package com.assistant

import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import com.assistant.runtime.*
import kotlin.math.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class SmartAssistPipeline {

    fun computeOptimalVector(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ): VectorDecision {

        val dx = endX - startX
        val dy = endY - startY

        val distance =
            hypot(dx,dy)

        val angle =
            abs(
                Math.toDegrees(
                    atan2(
                        dy.toDouble(),
                        dx.toDouble()
                    )
                ).toFloat()
            )

        val actionType =
            when {

                distance < 100f ->
                    ActionType.NONE

                angle in 60f..120f ->
                    ActionType.SHOT

                distance > 350f ->
                    ActionType.CROSS

                else ->
                    ActionType.PASS
            }

        return VectorDecision(
            shouldAct =
                actionType != ActionType.NONE,
            actionType =
                actionType,
            startX =
                startX,
            startY =
                startY,
            endX =
                endX,
            endY =
                endY,
            duration =
                duration
        )
    }

    fun createExecutionRequest(
        decision: VectorDecision
    ): ExecutionRequest {

        return ExecutionRequest(
            source =
                ExecutionSource.SMART_ASSIST,
            phase =
                when(decision.actionType){

                    ActionType.PASS -> 1

                    ActionType.SHOT -> 2

                    ActionType.CROSS -> 3

                    else -> 0
                },
            // Q68-94 (batch 5b): the OCR pipe carried actionClass=null like
            // the AGC legacy path, so executeDirectRequest skipped the
            // cooling check. Same mapping as phase above (NONE stays null:
            // no class to cool on).
            actionClass =
                when(decision.actionType){

                    ActionType.PASS -> ActionClass.PASS

                    ActionType.SHOT -> ActionClass.SHOT

                    ActionType.CROSS -> ActionClass.CROSS

                    else -> null
                },
            // no59 ITEM 1: the OCR pipe builds this request — it is the
            // owning producer, so its name is the honest engine label.
            engine =
                "SmartAssistPipeline",
            startX =
                decision.startX,
            startY =
                decision.startY,
            endX =
                decision.endX,
            endY =
                decision.endY,
            duration =
                decision.duration
        )
    }
}
