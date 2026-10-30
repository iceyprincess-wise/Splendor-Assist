class SmartAssistPipeline {

    fun computeOptimalVector(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ): VectorDecision {

        NativePipelineCache.cacheNode(0,startX,startY)
        NativePipelineCache.cacheNode(1,endX,endY)

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

        val confidence =
            (
                0.75f +
                (
                    distance / 1000f
                ).coerceAtMost(
                    0.25f
                )
            ).coerceAtMost(1f)

        return VectorDecision(
            shouldAct =
                actionType != ActionType.NONE,
            priority =
                (distance / 8f)
                    .toInt()
                    .coerceAtMost(100),
            actionType =
                actionType,
            confidence =
                confidence,
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
/* ======
SmartAssistPipeline Anchor
====== */

/* ========
SpaceOccupancyResult
======== */
