package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object BallPossessionEngine {

    /**
     * Item 15 (Letter B): stateless since the old hold-duration outputs
     * were deleted — no production reader ever consumed them (every live
     * gate reads hasPossession / ownerIndex / confidence), and the
     * change flag could not be wired into CounterPressRecognitionEngine:
     * it fires on ANY owner change (including a gain or a swap), while
     * the counter-press needs specifically a LOSS, which that engine
     * already derives itself from hasPossession. The old owner-tracking
     * state and the no53 reset() hook went with the fields — a pure
     * function needs no reset.
     */
    fun compute(
        ownership: BallOwnershipResult
    ): BallPossessionResult {

        if (!ownership.hasOwner || ownership.owner?.isUserTeam != true) {
            return BallPossessionResult(
                hasPossession = false
            )
        }

        return BallPossessionResult(
            hasPossession = true,
            ownerIndex = ownership.ownerIndex,
            confidence = ownership.confidence
        )
    }
}

/* ========
BallPossessionResult
======== */
