package com.assistant.overlay.interceptor

/**
 * Item 12 (12b) — Recovery vector (pure, no Android).
 *
 * ROOT CAUSE (PROVEN): `RecoveryPredictionEngine.predict()` computed a
 * recovery target (CENTER/LEFT/RIGHT/GOAL_AREA) on every evaluation,
 * but the target steered no gesture — the file itself admits "there is
 * no designed recovery-position gesture yet" (No64-D40). After a save
 * the keeper stayed exactly where the dive left him; after a rush he
 * stayed high up the pitch. RECOVER was never produced by the router
 * and its vector was a zero-length hold.
 *
 * This engine turns the predicted target into a real vector: from the
 * trained joystick centre toward the goal-line point for that target.
 * The caller offers it on quiet (TRACK) frames while recovering, so it
 * never competes with a live save.
 *
 * Goal-line Y = 0.90h is STATED TUNING: the goal area top is 0.75h
 * (defensive layer's six-yard top) and the line sits just inside the
 * bottom edge; 0.90h puts the keeper on the line without touching the
 * edge. The X span uses the calibrated posts when valid, else the
 * defensive layer's goal-area span [0.32w, 0.68w].
 */
object RecoveryVectorEngine {

    /** Goal-line depth (stated tuning — see header). */
    const val GOAL_LINE_Y_FRACTION = 0.90f
    const val GOAL_AREA_LEFT_FRACTION = 0.32f
    const val GOAL_AREA_RIGHT_FRACTION = 0.68f

    fun targetFor(
        target: RecoveryTarget,
        goalLeftX: Float,
        goalRightX: Float,
        width: Float,
        height: Float
    ): Pair<Float, Float> {
        val postsValid = goalRightX > goalLeftX && goalLeftX >= 0f
        val left = if (postsValid) goalLeftX else width * GOAL_AREA_LEFT_FRACTION
        val right = if (postsValid) goalRightX else width * GOAL_AREA_RIGHT_FRACTION
        val x = when (target) {
            RecoveryTarget.LEFT -> left
            RecoveryTarget.RIGHT -> right
            RecoveryTarget.CENTER, RecoveryTarget.GOAL_AREA -> (left + right) * 0.5f
        }
        return x.coerceIn(0f, width) to (height * GOAL_LINE_Y_FRACTION).coerceIn(0f, height)
    }

    fun vectorForTarget(
        target: RecoveryTarget,
        goalLeftX: Float,
        goalRightX: Float,
        width: Float,
        height: Float,
        joystickX: Float,
        joystickY: Float
    ): FloatArray {
        val (targetX, targetY) = targetFor(target, goalLeftX, goalRightX, width, height)
        return floatArrayOf(
            joystickX.coerceIn(0f, width),
            joystickY.coerceIn(0f, height),
            targetX,
            targetY
        )
    }
}
