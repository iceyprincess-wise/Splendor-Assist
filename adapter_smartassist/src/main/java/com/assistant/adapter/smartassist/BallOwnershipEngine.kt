package com.assistant.adapter.smartassist

object BallOwnershipEngine {

    private const val MAX_OWNERSHIP_DISTANCE = 60f

    fun compute(
        ball: BallDetectionResult,
        scene: SceneSnapshot
    ): BallOwnershipResult {

        val closest =
            ClosestPlayerEngine.compute(
                ball,
                scene
            )

        if (!closest.found || closest.player == null) {
            return BallOwnershipResult(
                hasOwner = false
            )
        }

        if (closest.distance > MAX_OWNERSHIP_DISTANCE) {
            return BallOwnershipResult(
                hasOwner = false,
                owner = closest.player,
                ownerIndex = closest.index,
                distanceToBall = closest.distance,
                confidence = 0f
            )
        }

        val confidence =
            (1f - (closest.distance / MAX_OWNERSHIP_DISTANCE))
                .coerceIn(0f, 1f) *
                closest.player.confidence

        return BallOwnershipResult(
            hasOwner = true,
            owner = closest.player,
            ownerIndex = closest.index,
            distanceToBall = closest.distance,
            confidence = confidence
        )
    }
}
