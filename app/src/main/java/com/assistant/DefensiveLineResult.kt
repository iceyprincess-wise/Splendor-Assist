package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class DefensiveLineResult(
    val found: Boolean,
    val averageX: Float = 0f,
    val minX: Float = 0f,
    val maxX: Float = 0f,
    val playerCount: Int = 0,
    val confidence: Float = 0f
)

/* ========
EntityAssociationEngine
======== */
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
