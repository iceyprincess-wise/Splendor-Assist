package com.assistant.adapter.smartassist

object BallPossessionEngine {

    private var previousOwner = -1
    private var possessionFrames = 0L

    fun compute(
        ownership: BallOwnershipResult
    ): BallPossessionResult {

        if (!ownership.hasOwner) {
            previousOwner = -1
            possessionFrames = 0L

            return BallPossessionResult(
                hasPossession = false
            )
        }

        val changed =
            ownership.ownerIndex != previousOwner

        if (changed) {
            previousOwner = ownership.ownerIndex
            possessionFrames = 1L
        } else {
            possessionFrames++
        }

        return BallPossessionResult(
            hasPossession = true,
            ownerIndex = ownership.ownerIndex,
            possessionFrames = possessionFrames,
            possessionChanged = changed,
            confidence = ownership.confidence
        )
    }
}
