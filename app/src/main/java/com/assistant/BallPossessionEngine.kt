package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object BallPossessionEngine {

    private var previousOwner = -1
    private var possessionFrames = 0L

    fun compute(
        ownership: BallOwnershipResult
    ): BallPossessionResult {

        if (!ownership.hasOwner || ownership.owner?.isUserTeam != true) {
            previousOwner = -1
            possessionFrames = 0L

            return BallPossessionResult(
                hasPossession = false
            )
        }

        val changed =
            ownership.owner.id != previousOwner

        if (changed) {
            previousOwner = ownership.owner.id
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

/* ========
BallPossessionResult
======== */
