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

            result += ThroughBallLane(
                lane = lane,
                viable = !lane.blocked && confidence >= 0.35f,
                leadDistance = leadDistance,
                confidence = confidence
            )
        }

        result.sortByDescending {
            it.confidence
        }

        return ThroughBallLaneAnalysis(result)
    }
}
/* ======
ThroughBallLaneAnalysisEngine Anchor
====== */

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
 * MODEL CONTRACT (for whoever supplies the asset):
 *   file      assets/splendor_detector.tflite
 *   input     [1, 320, 320, 3] float32 RGB, 0..1, nearest-neighbor resized
 *             from the capture frame (assumed RGBA_8888 byte layout)
 *   output    [1, 25, 6] float32: cxNorm, cyNorm, wNorm, hNorm, score, class
 *             class 0 = ball. Other classes reserved (1 player, 2 keeper).
 *
 * WIRING: VisionCore consults ballCandidateOrNull() BEFORE the heuristic
 * candidate is accepted. Trained wins only when ACTIVE and confident;
 * heuristic is computed every frame regardless, both as fallback and as a
 * cross-check - large disagreement is counted and logged, never hidden.
 *
 * PERFORMANCE (4GB device): input buffer and output arrays are
 * preallocated once - the per-frame inference path allocates nothing.
 */
