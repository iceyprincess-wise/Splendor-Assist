package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















object ShootingLaneAnalysisEngine {

    fun analyze(
        scene: SceneSnapshot,
        graph: PassingLaneGraph
    ): ShootingLaneAnalysis {

        if (!scene.goalDetected) {
            return ShootingLaneAnalysis()
        }

        val goalCenterX =
            (scene.goalLeftX + scene.goalRightX) * 0.5f

        val goalCenterY =
            (scene.goalTopY + scene.goalBottomY) * 0.5f

        val result = ArrayList<ShootingLane>(graph.lanes.size)

        graph.lanes.forEach { lane ->

            val shooter = lane.receiver

            // No68-F-D3: the goalkeeper is a user-team TrackedPlayer
            // (id -1, SceneTracker.kt) and a valid PassingLane receiver,
            // so a lane could name the keeper as the shooter. A keeper
            // shooting lane is nonsense; skip it.
            if (shooter.isGoalkeeper) return@forEach

            val distance =
                hypot(
                    (goalCenterX - shooter.x).toDouble(),
                    (goalCenterY - shooter.y).toDouble()
                ).toFloat()

            val confidence =
                (
                    lane.score *
                    (1f - lane.pressure) *
                    (1f - (distance / 1500f))
                ).coerceIn(0f,1f)

            // No68-F-D4: a non-finite distance/confidence (NaN goal
            // coords reach here — SceneSnapshot has no trusted gate)
            // used to enter the list and poison maxOf{} downstream
            // (ShootingHabitLearningEngine laneConfidence). Skip it.
            if (!distance.isFinite() || !confidence.isFinite()) return@forEach

            result += ShootingLane(
                shooter = shooter,
                distance = distance,
                confidence = confidence,
                viable = !lane.blocked && confidence >= 0.12f
            )
        }

        result.sortByDescending { it.confidence }
        return ShootingLaneAnalysis(result)
    }
}


/* ========
SmartAssistControlRoomBridge
======== */
