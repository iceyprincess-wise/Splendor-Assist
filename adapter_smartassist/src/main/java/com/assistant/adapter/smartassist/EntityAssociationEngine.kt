package com.assistant.adapter.smartassist

import com.assistant.admin.AdminConfigStore

/*
 * Matches this frame's player detections to the tracks we already know.
 *
 * REPAIRED (Task B): the old pass consumed a track's nearest detection even
 * when it was far outside the association gate - the detection vanished
 * (no new track) while the old track coasted on as a ghost, and the
 * mutate-while-scanning duplicate sweep could delete BOTH tracks of an
 * equal-confidence pair or neither. Together with the tracker's zombie
 * refresh these bugs inflated the tracked count to impossible numbers
 * (55v51 on a 22-man pitch), which made VisionTrust reject the frame -
 * so the WHOLE contributor stack sat gated off for ~97% of frames.
 *
 * Now: the distance gate is applied BEFORE a detection is consumed,
 * duplicate merging is deterministic (stronger wins, fresher breaks ties),
 * and the association distances answer to the admin store live.
 */
object EntityAssociationEngine {

    private var nextTrackId = 1

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val MAX_ASSOCIATION_DISTANCE: Float
        get() = AdminConfigStore.get("assist.track.assoc_dist", 120f)
    private val DUPLICATE_TRACK_DISTANCE: Float
        get() = AdminConfigStore.get("assist.track.dup_dist", 12f)

    private const val VELOCITY_SMOOTHING = 0.35f
    private const val CONFIDENCE_SMOOTHING = 0.25f

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
        val assigned = mutableSetOf<Int>()
        val maxDist = MAX_ASSOCIATION_DISTANCE

        // 1. Each track takes its nearest unassigned detection ONLY if it is
        //    inside the gate. A far detection stays available to spawn its
        //    own track instead of being consumed and thrown away.
        for (track in trackedPlayers) {
            var bestIdx = -1
            var bestD = Float.MAX_VALUE
            for (i in detections.indices) {
                if (i in assigned) continue
                val d = distance(track.x, track.y, detections[i].x, detections[i].y)
                if (d < bestD) {
                    bestD = d
                    bestIdx = i
                }
            }

            if (bestIdx >= 0 && bestD <= maxDist) {
                assigned.add(bestIdx)
                val nearest = detections[bestIdx]

                track.velocityX =
                    track.velocityX * (1f - VELOCITY_SMOOTHING) +
                    (nearest.x - track.x) * VELOCITY_SMOOTHING
                track.velocityY =
                    track.velocityY * (1f - VELOCITY_SMOOTHING) +
                    (nearest.y - track.y) * VELOCITY_SMOOTHING

                track.headingRadians =
                    kotlin.math.atan2(track.velocityY, track.velocityX)

                track.x = nearest.x
                track.y = nearest.y

                track.confidence = (
                    track.confidence * (1f - CONFIDENCE_SMOOTHING) +
                    nearest.confidence * CONFIDENCE_SMOOTHING
                ).coerceIn(0f, 1f)

                track.lastSeenFrame = frameNumber
            } else {
                // nothing believable this frame: coast and fade
                track.x += track.velocityX
                track.y += track.velocityY
                track.confidence = (track.confidence * 0.98f).coerceAtLeast(0f)
            }
        }

        // 2. Detections no track claimed become new tracks.
        for (i in detections.indices) {
            if (i in assigned) continue
            val detection = detections[i]
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

        // 3. Deterministic duplicate merge: two tracks on the same spot are
        //    one player. Stronger confidence wins; fresher sighting breaks
        //    ties; identity-based set so equal pairs lose exactly one member.
        val dupDist = DUPLICATE_TRACK_DISTANCE
        val doomed = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<TrackedPlayer, Boolean>()
        )
        for (i in trackedPlayers.indices) {
            val a = trackedPlayers[i]
            if (a in doomed) continue
            for (j in i + 1 until trackedPlayers.size) {
                val b = trackedPlayers[j]
                if (b in doomed) continue
                if (distance(a.x, a.y, b.x, b.y) < dupDist) {
                    val loser = when {
                        a.confidence != b.confidence ->
                            if (a.confidence < b.confidence) a else b
                        a.lastSeenFrame != b.lastSeenFrame ->
                            if (a.lastSeenFrame < b.lastSeenFrame) a else b
                        else -> b
                    }
                    doomed.add(loser)
                    if (loser === a) break
                }
            }
        }
        if (doomed.isNotEmpty()) {
            val it = trackedPlayers.iterator()
            while (it.hasNext()) {
                if (it.next() in doomed) it.remove()
            }
        }
    }
}
