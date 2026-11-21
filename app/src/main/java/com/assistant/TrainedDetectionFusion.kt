package com.assistant

import kotlin.math.hypot
import kotlin.math.max

/*
 * Trained-detection fusion (queue item 21, v3 TFLite detector).
 *
 * Pure Kotlin — no Android imports — so the harness can compile and pin
 * it directly. These are the "joins, never replaces" rules: the
 * heuristic is computed every frame and owns every frame; a trained
 * result only wins when it is FRESH (the freshness gate IS the
 * slowness protection — a ~1 s inference degrades gracefully to
 * heuristic-only because its result is stale by the time it lands).
 *
 * The engine calls these from VisionCore-time with the latest async
 * inference snapshot; the engine itself owns the holder, the counters
 * and the throttled logs. Nothing here touches a detector, a buffer,
 * or the interpreter.
 *
 * STATED TUNING (to be calibrated on-device, Splendor's phone):
 *   FRESH_MS = 250 — a trained result older than this is ignored.
 *   KEEPER_MIN_CONFIDENCE / PLAYER_MIN_CONFIDENCE = 0.50.
 *   PLAYER_MATCH_PX = 40 — a trained player within this of a heuristic
 *   player confirms it; further away it is an ADDITION, never a
 *   replacement.
 * Preserved (not tuning): ball acceptance 0.35 (MIN_BALL_SCORE, as
 * built) and DIVERGENCE_PX = 80f (as built).
 */
object TrainedDetectionFusion {

    const val FRESH_MS = 250L
    const val DIVERGENCE_PX = 80f
    const val BALL_MIN_CONFIDENCE = 0.35f
    const val KEEPER_MIN_CONFIDENCE = 0.50f
    const val PLAYER_MIN_CONFIDENCE = 0.50f
    const val PLAYER_MATCH_PX = 40f

    data class BallFusion(
        // True when the trained ball wins this frame.
        val useTrained: Boolean,
        // Non-null exactly when useTrained.
        val candidate: BallCandidate?,
        // True when the trained ball was fresh and confident but
        // diverged > DIVERGENCE_PX from the heuristic — the HEURISTIC
        // wins (the old code let trained win only because it was the
        // same frame; that assumption no longer holds for async
        // inference). The caller counts + throttled-logs this.
        val divergent: Boolean
    )

    /** Age of a trained result relative to the frame being fused, ms. */
    fun ageMs(frameTimestampNanos: Long, sourceTimestampNanos: Long): Long {
        val age = (frameTimestampNanos - sourceTimestampNanos) / 1_000_000L
        return if (age < 0L) 0L else age
    }

    fun isFresh(ageMs: Long): Boolean = ageMs in 0L..FRESH_MS

    fun bestBall(detections: List<Yolo11Decoder.YoloDetection>): Yolo11Decoder.YoloDetection? =
        detections.filter { it.classId == Yolo11Decoder.CLASS_BALL }
            .maxByOrNull { it.confidence }

    fun bestKeeper(detections: List<Yolo11Decoder.YoloDetection>): Yolo11Decoder.YoloDetection? =
        detections.filter { it.classId == Yolo11Decoder.CLASS_KEEPER }
            .maxByOrNull { it.confidence }

    fun playerDetections(detections: List<Yolo11Decoder.YoloDetection>): List<Yolo11Decoder.YoloDetection> =
        detections.filter {
            (it.classId == Yolo11Decoder.CLASS_MY_PLAYER ||
                it.classId == Yolo11Decoder.CLASS_OPPONENT_PLAYER) &&
                it.confidence >= PLAYER_MIN_CONFIDENCE
        }

    /**
     * Ball: trained wins when fresh AND confident (>= 0.35) AND (no
     * heuristic ball OR within DIVERGENCE_PX of it). Fresh but
     * divergent > 80 px -> heuristic wins, divergent = true.
     * Anything else (stale, weak, absent) -> heuristic wins silently.
     */
    fun selectBall(
        ball: Yolo11Decoder.YoloDetection?,
        heuristic: BallCandidate?,
        ageMs: Long
    ): BallFusion {
        if (ball == null || ball.classId != Yolo11Decoder.CLASS_BALL) {
            return BallFusion(false, null, false)
        }
        if (!isFresh(ageMs)) return BallFusion(false, null, false)
        if (ball.confidence < BALL_MIN_CONFIDENCE) return BallFusion(false, null, false)
        val candidate = BallCandidate(
            centerX = ball.centerX,
            centerY = ball.centerY,
            radius = max(ball.width, ball.height) / 2f,
            pixelCount = 0, // Model box area is not a count of observed matching pixels.
            score = ball.confidence.coerceIn(0f, 1f)
        ).let {
            // radius is never zero: a degenerate box still needs a
            // positive radius for BallDetector's geometry.
            if (it.radius < 1f) it.copy(radius = 1f) else it
        }
        if (heuristic != null &&
            hypot(candidate.centerX - heuristic.centerX, candidate.centerY - heuristic.centerY) > DIVERGENCE_PX
        ) {
            return BallFusion(false, null, true)
        }
        return BallFusion(true, candidate, false)
    }

    /**
     * Keeper: trained wins when fresh AND confident (>= 0.50), via the
     * GameStateFusion seam (GoalkeeperDetectionResult into fuse).
     * GoalkeeperDetector.detect is blob-only by design and untouched.
     * Null means "use the heuristic keeper".
     */
    fun selectKeeper(
        keeper: Yolo11Decoder.YoloDetection?,
        ageMs: Long
    ): GoalkeeperDetectionResult? {
        if (keeper == null || keeper.classId != Yolo11Decoder.CLASS_KEEPER) return null
        if (!isFresh(ageMs)) return null
        if (keeper.confidence < KEEPER_MIN_CONFIDENCE) return null
        return GoalkeeperDetectionResult(
            detected = true,
            x = keeper.centerX,
            y = keeper.centerY,
            confidence = keeper.confidence.coerceIn(0f, 1f)
        )
    }

    /**
     * Players: MERGE, never replace. Heuristic detections are always
     * kept; a trained player with no heuristic match within
     * PLAYER_MATCH_PX is ADDED (isUserTeam = class is MyPlayer); a
     * matched pair confirms the heuristic one (its x/y stand).
     * Stale trained data returns the heuristic result untouched.
     * Confidence is the max of the heuristic confidence and the best
     * added trained confidence — both measured, nothing fabricated.
     * PlayerDetector.detect is blob-only by design and untouched.
     */
    fun mergePlayers(
        trained: List<Yolo11Decoder.YoloDetection>,
        heuristic: PlayerDetectionResult,
        ageMs: Long
    ): PlayerDetectionResult {
        if (!isFresh(ageMs)) return heuristic
        val merged = ArrayList<PlayerDetection>(heuristic.detections)
        var bestAddedConf = 0f
        for (t in trained) {
            if (t.classId != Yolo11Decoder.CLASS_MY_PLAYER &&
                t.classId != Yolo11Decoder.CLASS_OPPONENT_PLAYER
            ) continue
            if (t.confidence < PLAYER_MIN_CONFIDENCE) continue
            val matched = heuristic.detections.any { h ->
                hypot(t.centerX - h.x, t.centerY - h.y) <= PLAYER_MATCH_PX
            }
            if (!matched) {
                merged.add(
                    PlayerDetection(
                        x = t.centerX,
                        y = t.centerY,
                        confidence = t.confidence.coerceIn(0f, 1f),
                        isUserTeam = t.classId == Yolo11Decoder.CLASS_MY_PLAYER
                    )
                )
                if (t.confidence > bestAddedConf) bestAddedConf = t.confidence
            }
        }
        if (merged.size == heuristic.detections.size) return heuristic
        return PlayerDetectionResult(
            detected = merged.isNotEmpty(),
            playerCount = merged.size,
            confidence = max(heuristic.confidence, bestAddedConf),
            detections = merged
        )
    }
}
