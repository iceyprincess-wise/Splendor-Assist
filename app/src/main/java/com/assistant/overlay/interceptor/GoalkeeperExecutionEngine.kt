package com.assistant.overlay.interceptor

object GoalkeeperExecutionEngine {

    fun vectorFor(
        action: GoalkeeperAction,
        width: Float,
        height: Float
    ): FloatArray {

        return when (action) {

            GoalkeeperAction.DIVE_LEFT ->
                DiveLeftActionEngine.vector(width, height)

            GoalkeeperAction.DIVE_RIGHT ->
                DiveRightActionEngine.vector(width, height)

            GoalkeeperAction.BLOCK_LEFT ->
                BlockLeftActionEngine.vector(width, height)

            GoalkeeperAction.BLOCK_RIGHT ->
                BlockRightActionEngine.vector(width, height)

            GoalkeeperAction.RUSH_OUT ->
                RushOutActionEngine.vector(width, height)

            GoalkeeperAction.CLAIM_CROSS ->
                CrossClaimActionEngine.vector(width, height)

            GoalkeeperAction.PUNCH_CROSS ->
                CrossPunchActionEngine.vector(width, height)

            GoalkeeperAction.HOLD,
            GoalkeeperAction.RECOVER,
            GoalkeeperAction.TRACK ->
                HoldPositionActionEngine.vector(width, height)
        }
    }
}
