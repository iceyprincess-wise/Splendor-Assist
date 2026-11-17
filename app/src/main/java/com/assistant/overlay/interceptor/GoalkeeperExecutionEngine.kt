package com.assistant.overlay.interceptor

object GoalkeeperExecutionEngine {

    // No64-D32: the producing action engine's name, for the dispatch log.
    // GK save offers used to carry engine="OmnipotentGoalkeeperEngine" (the
    // offerer), so a dispatch line could never say which action engine
    // fired — and actionClass is null on GK offers, so the line printed
    // action=? as well. The Omnipotent offer site sets
    // engine = engineNameFor(finalAction), so dispatch lines now read
    // engine=<ActionEngineName>. Pure (no Android, no I/O) — pinned.
    fun engineNameFor(action: GoalkeeperAction): String {
        return when (action) {
            GoalkeeperAction.DIVE_LEFT -> "DiveLeftActionEngine"
            GoalkeeperAction.DIVE_RIGHT -> "DiveRightActionEngine"
            GoalkeeperAction.DIVE_BOTTOM_LEFT -> "BottomLeftActionEngine"
            GoalkeeperAction.DIVE_BOTTOM_RIGHT -> "BottomRightActionEngine"
            GoalkeeperAction.BLOCK_LEFT -> "BlockLeftActionEngine"
            GoalkeeperAction.BLOCK_RIGHT -> "BlockRightActionEngine"
            GoalkeeperAction.RUSH_OUT -> "RushOutActionEngine"
            GoalkeeperAction.CLAIM_CROSS -> "CrossClaimActionEngine"
            GoalkeeperAction.PUNCH_CROSS -> "CrossPunchActionEngine"
            GoalkeeperAction.RECOVER -> "HoldPositionActionEngine"
            GoalkeeperAction.TRACK -> "HoldPositionActionEngine"
            GoalkeeperAction.HOLD -> "HoldPositionActionEngine"
            GoalkeeperAction.JUMP_TOP_CENTER -> "JumpTopCenterActionEngine"
            GoalkeeperAction.JUMP_TOP_LEFT -> "JumpTopLeftActionEngine"
            GoalkeeperAction.JUMP_TOP_RIGHT -> "JumpTopRightActionEngine"
            GoalkeeperAction.REACH_LEFT_CENTER -> "ReachLeftCenterActionEngine"
            GoalkeeperAction.REACH_RIGHT_CENTER -> "ReachRightCenterActionEngine"
            GoalkeeperAction.CATCH_CENTER -> "CatchCenterActionEngine"
            GoalkeeperAction.REFLEX_CENTER -> "ReflexCenterActionEngine"
            GoalkeeperAction.PARRY_BOTTOM_CENTER -> "ParryBottomCenterActionEngine"
            GoalkeeperAction.AERIAL_CLAIM -> "AerialClaimActionEngine"
            GoalkeeperAction.REBOUND_SAVE -> "ReboundSaveActionEngine"
        }
    }

    fun vectorFor(
        action: GoalkeeperAction,
        width: Float,
        height: Float
    ): FloatArray {

        val vector = when (action) {

            GoalkeeperAction.DIVE_LEFT ->
                DiveLeftActionEngine.vector(width, height)

            GoalkeeperAction.DIVE_RIGHT ->
                DiveRightActionEngine.vector(width, height)

            GoalkeeperAction.DIVE_BOTTOM_LEFT ->
                BottomLeftActionEngine.vector(width, height)

            GoalkeeperAction.DIVE_BOTTOM_RIGHT ->
                BottomRightActionEngine.vector(width, height)

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

            GoalkeeperAction.RECOVER ->
                HoldPositionActionEngine.vector(width, height)

            GoalkeeperAction.TRACK ->
                HoldPositionActionEngine.vector(width, height)

            GoalkeeperAction.HOLD ->
                HoldPositionActionEngine.vector(width, height)

            GoalkeeperAction.JUMP_TOP_CENTER ->
                JumpTopCenterActionEngine.vector(width, height)

            GoalkeeperAction.JUMP_TOP_LEFT ->
                JumpTopLeftActionEngine.vector(width, height)

            GoalkeeperAction.JUMP_TOP_RIGHT ->
                JumpTopRightActionEngine.vector(width, height)

            GoalkeeperAction.REACH_LEFT_CENTER ->
                ReachLeftCenterActionEngine.vector(width, height)

            GoalkeeperAction.REACH_RIGHT_CENTER ->
                ReachRightCenterActionEngine.vector(width, height)

            GoalkeeperAction.CATCH_CENTER ->
                CatchCenterActionEngine.vector(width, height)

            GoalkeeperAction.REFLEX_CENTER ->
                ReflexCenterActionEngine.vector(width, height)

            GoalkeeperAction.PARRY_BOTTOM_CENTER ->
                ParryBottomCenterActionEngine.vector(width, height)

            GoalkeeperAction.AERIAL_CLAIM ->
                AerialClaimActionEngine.vector(width, height)

            GoalkeeperAction.REBOUND_SAVE ->
                ReboundSaveActionEngine.vector(width, height)
        }

        // No64-D25 (F5): joystick-driven actions start on the trained
        // joystick centre, not on the fixed (0.50w, 0.72h) point — the old
        // start touched no control. RUSH_OUT / CLAIM_CROSS / PUNCH_CROSS /
        // AERIAL_CLAIM keep their current starts, as do JUMP_TOP_*,
        // REBOUND_SAVE and the zero-length HOLD/TRACK/RECOVER vectors.
        // ControlMappingTrainer.joystickCenterX/Y() are the probed
        // constants (0.182, 0.648); a zero/unset centre falls back to the
        // engine's own start (the vector is left untouched).
        return when (action) {
            GoalkeeperAction.DIVE_LEFT,
            GoalkeeperAction.DIVE_RIGHT,
            GoalkeeperAction.DIVE_BOTTOM_LEFT,
            GoalkeeperAction.DIVE_BOTTOM_RIGHT,
            GoalkeeperAction.BLOCK_LEFT,
            GoalkeeperAction.BLOCK_RIGHT,
            GoalkeeperAction.REACH_LEFT_CENTER,
            GoalkeeperAction.REACH_RIGHT_CENTER,
            GoalkeeperAction.CATCH_CENTER,
            GoalkeeperAction.REFLEX_CENTER,
            GoalkeeperAction.PARRY_BOTTOM_CENTER -> {
                val joyX = com.assistant.ControlMappingTrainer.joystickCenterX()
                val joyY = com.assistant.ControlMappingTrainer.joystickCenterY()
                if (joyX > 0f && joyY > 0f) {
                    vector[0] = width * joyX
                    vector[1] = height * joyY
                }
                vector
            }

            else -> vector
        }
    }
}
