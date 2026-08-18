package com.assistant.adapter.smartassist

object GameStateFusion {

    fun fuse(
        ball: BallDetectionResult,
        motion: MotionResult,
        players: PlayerDetectionResult,
        goalkeeper: GoalkeeperDetectionResult,
        goal: GoalDetectionResult,
        field: FieldLineDetectionResult
    ): GameStateSnapshot {

        
        val confidence =
            (
                ball.confidence +
                players.confidence
            ) / 2f

        val userPlayers =
            players.detections.count { it.isUserTeam }

        val opponentPlayers =
            players.detections.count { !it.isUserTeam }

        return GameStateSnapshot(
            ballDetected = ball.detected,
            playerDetected = players.detected,
            userPlayers = userPlayers,
            opponentPlayers = opponentPlayers,

            ballX = ball.x,
            ballY = ball.y,

            ballVelocityX = motion.velocityX,
            ballVelocityY = motion.velocityY,

            goalkeeperDetected =
                goalkeeper.detected,

            goalkeeperX =
                goalkeeper.x,

            goalkeeperY =
                goalkeeper.y,

            goalkeeperConfidence =
                goalkeeper.confidence,

            ballSpeed = motion.speed,
            ballDirection = motion.directionRadians,

            goalDetected = goal.detected,
            goalLeftX = goal.leftX,
            goalRightX = goal.rightX,
            goalTopY = goal.topY,
            goalBottomY = goal.bottomY,
            goalConfidence = goal.confidence,

            touchLinesDetected = field.touchLinesDetected,
            penaltyAreaDetected = field.penaltyAreaDetected,
            goalAreaDetected = field.goalAreaDetected,
            centerCircleDetected = field.centerCircleDetected,
            fieldConfidence = field.confidence,

            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
