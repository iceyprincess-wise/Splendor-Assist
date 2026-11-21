package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object ThroughBallLaneAnalysisEngine {

    fun analyze(
        graph: PassingLaneGraph
    ): ThroughBallLaneAnalysis {

        val result = ArrayList<ThroughBallLane>(graph.lanes.size)

        for (lane in graph.lanes) {

            val leadDistance =
                (lane.distance * 0.18f)
                    .coerceIn(15f,120f)

            val confidence =
                (
                    lane.score *
                    (1f - lane.pressure)
                ).coerceIn(0f,1f)

            // B9: direction-aware lead vector. The old lead was a
            // directionless scalar that AGC applied as receiverY −
            // lead — always up-screen, regardless of attack direction
            // (attack is along X) or the receiver's run. The lead now
            // points along the RECEIVER'S RUN when the receiver is
            // really moving (px/s speed ≥ 50, B6 velocity), else along
            // the LANE (passer → receiver). A degenerate lane (zero
            // length, no motion) yields a zero vector — never a −Y
            // fallback. D15: LONG_BALL is not touched here — no
            // long-ball classification exists on this path.
            val (leadX, leadY) = leadVector(lane, leadDistance)

            result += ThroughBallLane(
                lane = lane,
                viable = !lane.blocked && confidence >= 0.35f,
                leadDistance = leadDistance,
                confidence = confidence,
                leadX = leadX,
                leadY = leadY
            )
        }

        result.sortByDescending {
            it.confidence
        }

        return ThroughBallLaneAnalysis(result)
    }

    /**
     * B9 pure lead-vector core (harness-pinned). Receiver run first:
     * when the receiver's px/s velocity (B6) is a real run, the lead
     * follows the run; otherwise it follows the lane. Returns (0,0)
     * when neither direction exists.
     */
    fun leadVector(lane: PassingLane, leadDistance: Float): Pair<Float, Float> {
        val receiverSpeed = kotlin.math.hypot(
            lane.receiver.velocityXPerSecond.toDouble(),
            lane.receiver.velocityYPerSecond.toDouble()
        ).toFloat()
        if (receiverSpeed >= RUN_DIRECTION_MIN_PX_PER_S &&
            lane.receiver.velocityXPerSecond.isFinite() &&
            lane.receiver.velocityYPerSecond.isFinite()
        ) {
            val inv = 1f / receiverSpeed
            return Pair(
                lane.receiver.velocityXPerSecond * inv * leadDistance,
                lane.receiver.velocityYPerSecond * inv * leadDistance
            )
        }
        val dx = lane.receiver.x - lane.passer.x
        val dy = lane.receiver.y - lane.passer.y
        val laneLength = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (!laneLength.isFinite() || laneLength <= 0f) return Pair(0f, 0f)
        return Pair(dx / laneLength * leadDistance, dy / laneLength * leadDistance)
    }

    /** A receiver slower than this is standing, not running a lane. */
    const val RUN_DIRECTION_MIN_PX_PER_S = 50f
}

/* ========
TrainedDetectionEngine
======== */
/*
 * TRAINED DETECTION ENGINE (Task C - detection upgrade path).
 *
 * HONEST STATUS CONTRACT - this engine never lies about what it is:
 *
 *   UNINITIALIZED -> initialize() not yet called.
 *   MISSING_MODEL -> no model asset on device. The heuristic pipeline runs
 *                    EXACTLY as before; this engine is a no-op.
 *   LOAD_ERROR    -> asset exists but failed to load; heuristic unchanged.
 *   LOADED        -> interpreter ready, ZERO inferences run yet. Still not
 *                    claimed as active.
 *   ACTIVE        -> at least one REAL on-device inference has completed
 *                    and is logged ("TFLITE ACTIVE"). Only this state may
 *                    ever be reported as "trained model running".
 *   DISABLED      -> too many consecutive inference failures; engine shut
 *                    itself off loudly and heuristic took over again.
 *
 * MODEL CONTRACT (queue item 21, v3 — replaces the old 320 / [1,25,6]
 * contract; the old file is gone):
 *   file      side-load /sdcard/Splendor-Assist/splendor_detector.tflite
 *             (SHA-256-pinned v3, AGPL-3.0), asset fallback of the same
 *             name; the 80 MB model is never in the repo or the APK
 *   input     [1, 640, 640, 3] float32 RGB, 0..1, LETTERBOX resized
 *             (scale = min(640/w, 640/h), grey-114 pad) from the capture
 *             frame (assumed RGBA_8888 byte layout) — stretch loses to
 *             letterbox for this model (D2), so the native stretch path
 *             is bypassed for v3 input
 *   output    [1, 9, 8400] float32: rows 0-3 xywh in 640-space, rows 4-8
 *             per-class scores (sigmoid already applied); classes
 *             0 Ball, 1 Buttons, 2 Keeper, 3 MyPlayer, 4 OpponentPlayer
 *             (hard-coded in Yolo11Decoder — the .tflite carries no
 *             labels). Decode + NMS in Yolo11Decoder; fusion rules in
 *             TrainedDetectionFusion.
 *
 * WIRING: inference runs ASYNC on a dedicated loop (never inside
 * VisionCore.process()). VisionCore offers each frame via submitFrame()
 * (cadence-gated, 500 ms) and reads the latest result through
 * fusedBall() / fusedKeeper() / mergedPlayers(), each gated on
 * freshness (250 ms) + confidence; the heuristic is computed every
 * frame regardless, both as fallback and as cross-check — a fresh
 * trained ball that disagrees by > 80 px is counted and logged and
 * the heuristic wins. Buttons are log-only (no consumer exists).
 *
 * PERFORMANCE (4GB device): input buffer and output arrays are
 * preallocated once; the only per-submit allocation is the engine-owned
 * frame snapshot (~737 KB at 288x640, at most one per 500 ms).
 */
