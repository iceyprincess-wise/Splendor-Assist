data class ShootingLane(
    val shooter: TrackedPlayer,
    val targetX: Float,
    val targetY: Float,
    val distance: Float,
    val confidence: Float,
    val viable: Boolean
)

