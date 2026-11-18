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
 *
 * No68-D-D24: this block documents EntityAssociationEngine, not
 * DefensiveLineResult — it is the ONLY copy of the Task B repair note
 * (EntityAssociationEngine.kt itself does not carry it), so it is KEPT
 * here rather than deleted. It belongs at the top of
 * EntityAssociationEngine.kt; moving it is queued (QD-9, needs the
 * owning family — this file is outside Family D's scope to move it to).
 */
