package com.assistant.adapter.smartassist

object EntityAssociationEngine {

    private var nextTrackId = 1

    private const val MAX_ASSOCIATION_DISTANCE = 120f

    private const val DUPLICATE_TRACK_DISTANCE = 12f

    private const val VELOCITY_SMOOTHING = 0.35f

    private const val CONFIDENCE_SMOOTHING = 0.25f

    private const val MAX_RECOVERY_FRAMES = 10L

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {

        val dx = x1 - x2
        val dy = y1 - y2

        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun associate(
        trackedPlayers: MutableList<TrackedPlayer>,
        detections: List<PlayerDetection>,
        frameNumber: Long
    ) {

        val assignedDetections = mutableSetOf<Int>()
        val unusedDetections =
            detections.indices.toMutableSet()

        trackedPlayers.forEach { track ->

            val nearestIndex = detections.indices
                .filter { it !in assignedDetections }
                .minByOrNull {
                distance(track.x, track.y, detections[it].x, detections[it].y)
            }

            if(nearestIndex != null){

                val nearest = detections[nearestIndex]
                assignedDetections.add(nearestIndex)
                unusedDetections.remove(nearestIndex)

                val d = distance(
                    track.x,
                    track.y,
                    nearest.x,
                    nearest.y
                )

                if(d <= MAX_ASSOCIATION_DISTANCE){

                    track.velocityX =
                        track.velocityX * (1f - VELOCITY_SMOOTHING) +
                        (nearest.x - track.x) * VELOCITY_SMOOTHING
                    track.velocityY =
                        track.velocityY * (1f - VELOCITY_SMOOTHING) +
                        (nearest.y - track.y) * VELOCITY_SMOOTHING

                    track.headingRadians =
                        kotlin.math.atan2(
                            track.velocityY,
                            track.velocityX
                        )

                    track.x = nearest.x
                    track.y = nearest.y

                    track.confidence =
                        (
                            track.confidence * (1f - CONFIDENCE_SMOOTHING) +
                            nearest.confidence * CONFIDENCE_SMOOTHING
                        ).coerceIn(0f,1f)

                    track.lastSeenFrame = frameNumber
                } else {

                    track.x += track.velocityX
                    track.y += track.velocityY

                    track.confidence =
                        (track.confidence * 0.98f).coerceAtLeast(0f)
                }
            } else {

                track.x += track.velocityX
                track.y += track.velocityY

                track.confidence =
                    (track.confidence * 0.98f).coerceAtLeast(0f)
            }
        }

        unusedDetections.forEach { index ->

            val detection = detections[index]

            trackedPlayers.add(
                TrackedPlayer(
                    id = nextTrackId++,

                    x = detection.x,
                    y = detection.y,

                    velocityX = 0f,
                    velocityY = 0f,

                    confidence = detection.confidence,

                    isUserTeam = detection.isUserTeam,

                    lastSeenFrame = frameNumber
                )
            )
        }

        trackedPlayers.removeAll { track ->
            trackedPlayers.any { other ->
                other !== track &&
                distance(
                    track.x,
                    track.y,
                    other.x,
                    other.y
                ) < DUPLICATE_TRACK_DISTANCE &&
                other.confidence >= track.confidence
            } || (frameNumber - track.lastSeenFrame > MAX_RECOVERY_FRAMES && track.confidence <= 0f)
        }
    }
}
