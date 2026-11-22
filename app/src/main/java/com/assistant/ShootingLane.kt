package com.assistant

// No68-F-D2: targetX/targetY are removed. They had zero readers
// repo-wide (ActiveGestureController reads only .confidence and
// .distance off a lane) and were loop-invariant — every lane in one
// analyze() call carried the same goal-centre point — so they carried
// no per-lane information. Aim is taken from the frame directly by the
// SHOT contributors instead.
data class ShootingLane(
    val shooter: TrackedPlayer,
    val distance: Float,
    val confidence: Float,
    val viable: Boolean
)
