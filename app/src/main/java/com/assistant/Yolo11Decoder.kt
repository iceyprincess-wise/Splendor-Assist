package com.assistant

import kotlin.math.max
import kotlin.math.min

/*
 * YOLO11 raw-output decoder (queue item 21, v3 TFLite detector).
 *
 * Pure Kotlin — no Android imports — so the harness can compile and pin
 * it directly. The model is mine-jgaan-3-yolo11m-t1 (Roboflow), exported
 * ONNX -> TFLite float32. Licence: AGPL-3.0 (flagged by Roboflow on
 * download; the model file carries that licence wherever it ships).
 *
 * Output contract (verified against the .tflite + the sibling ONNX/.pt,
 * harness-batch-tflite/investigation/04-model-verification.md):
 *   input  (1, 640, 640, 3) float32 NHWC, 0..1
 *   output (1, 9, 8400) float32 — rows 0-3 = cx, cy, w, h in 640-space,
 *   rows 4-8 = per-class scores. The exporter already applied sigmoid:
 *   class rows are probabilities in [0, 1] (verified: min 0.0, max 0.974).
 *
 * CLASS ORDER — hard-coded here, NEVER read from the model file: the
 * .tflite carries zero metadata (metadata_buffer count = 0, no class
 * names anywhere in its bytes). The order is pinned ONLY by the sibling
 * ONNX metadata_props and the .pt model.names, which agree:
 *   0 Ball, 1 Buttons, 2 Keeper, 3 MyPlayer, 4 OpponentPlayer.
 * If the model is ever retrained with a different order, THIS list is
 * the one place that must change with it.
 *
 * Preprocess (D2 experiment, harness-batch-tflite/d2-stretch-vs-letterbox.py):
 * letterbox WINS over stretch. On frame A (640x296) stretch produced 6
 * spurious Keeper boxes (players double-classified Keeper/MyPlayer on
 * the same box) and lost 2 OpponentPlayer + 2 MyPlayer; on frame C
 * stretch dropped the Ball 0.974 -> 0.918, invented a second Ball at
 * 0.709, and lost the MyPlayer entirely. Letterbox reproduced the .pt
 * run exactly (21 detections frame A, Ball 0.974 frame C). The engine
 * therefore letterboxes; decode() inverts the letterbox (subtract pad,
 * divide by scale). The native stretch path is bypassed for v3 input.
 */
object Yolo11Decoder {

    const val INPUT_SIZE = 640
    const val CANDIDATE_COUNT = 8400
    const val OUTPUT_ROWS = 9
    const val CLASS_COUNT = 5

    const val CLASS_BALL = 0
    const val CLASS_BUTTONS = 1
    const val CLASS_KEEPER = 2
    const val CLASS_MY_PLAYER = 3
    const val CLASS_OPPONENT_PLAYER = 4

    // Pinned by the ONNX metadata_props + .pt model.names (see header).
    // The .tflite file itself carries no labels — do not try to read them.
    val CLASS_NAMES = arrayOf("Ball", "Buttons", "Keeper", "MyPlayer", "OpponentPlayer")

    // Verified decode thresholds (04-model-verification.md): conf 0.25,
    // class-aware NMS at IoU 0.45. The .pt cross-check used the same pair.
    const val CONF_THRESHOLD = 0.25f
    const val NMS_IOU = 0.45f

    // Letterbox pad colour (YOLO convention, grey 114) — the pad value
    // itself is the engine's resample business; the decoder only needs
    // the geometry (scale + pad offsets) to invert it.
    const val LETTERBOX_PAD = 114

    data class YoloDetection(
        val classId: Int,
        val confidence: Float,
        // Centre + size in CAPTURE coordinates (the letterbox inverse
        // has already been applied; x/y are frame pixels).
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float
    ) {
        fun left() = centerX - width / 2f
        fun top() = centerY - height / 2f
        fun right() = centerX + width / 2f
        fun bottom() = centerY + height / 2f
        fun className(): String = CLASS_NAMES.getOrElse(classId) { "class$classId" }
    }

    data class LetterboxGeometry(
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    /**
     * Letterbox geometry for a w x h capture frame into a square input:
     * scale = min(inputSize / w, inputSize / h); the resized image is
     * centred, so padX = (inputSize - w * scale) / 2 (0 when width is
     * the binding dimension), likewise padY. Portrait 288x640 -> scale
     * 1.0, padX 176, padY 0; landscape 640x288 -> scale 1.0, padX 0,
     * padY 176. Shared by the engine resample and this decoder's
     * inverse, so the two can never drift apart.
     */
    fun letterboxGeometry(frameW: Int, frameH: Int, inputSize: Int = INPUT_SIZE): LetterboxGeometry {
        if (frameW <= 0 || frameH <= 0 || inputSize <= 0) return LetterboxGeometry(1f, 0f, 0f)
        val scale = min(inputSize.toFloat() / frameW, inputSize.toFloat() / frameH)
        val padX = (inputSize - frameW * scale) / 2f
        val padY = (inputSize - frameH * scale) / 2f
        return LetterboxGeometry(scale, padX, padY)
    }

    fun iou(a: YoloDetection, b: YoloDetection): Float {
        val ix1 = max(a.left(), b.left())
        val iy1 = max(a.top(), b.top())
        val ix2 = min(a.right(), b.right())
        val iy2 = min(a.bottom(), b.bottom())
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val areaA = max(0f, a.width) * max(0f, a.height)
        val areaB = max(0f, b.width) * max(0f, b.height)
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun sigmoid(x: Float): Float {
        // Guarded against overflow the same way the Python verifier is:
        // scores in [0, 1] never reach this path (see decode()).
        return (1.0 / (1.0 + kotlin.math.exp(-x.toDouble()))).toFloat()
    }

    /**
     * Decode one inference's raw output.
     *
     * @param rows output[0]: 9 rows x 8400 candidates (rows 0-3 xywh in
     *   640-space, rows 4-8 per-class scores). Shape is validated by the
     *   caller at load; a malformed array here decodes to empty, never
     *   throws — a bad decode must degrade to heuristic-only, not crash
     *   the inference loop.
     * @param frameW/frameH capture dimensions the boxes map back into.
     * @param geometry the letterbox geometry the resample used.
     * @return detections in capture coordinates, highest confidence
     *   first, class-aware NMS applied.
     */
    fun decode(
        rows: Array<FloatArray>,
        frameW: Int,
        frameH: Int,
        geometry: LetterboxGeometry
    ): List<YoloDetection> {
        if (rows.size != OUTPUT_ROWS) return emptyList()
        for (row in rows) if (row.size != CANDIDATE_COUNT) return emptyList()
        if (frameW <= 0 || frameH <= 0) return emptyList()
        if (!geometry.scale.isFinite() || geometry.scale <= 0f) return emptyList()

        val xywh = rows
        val scoresRaw = Array(CLASS_COUNT) { c -> rows[4 + c] }

        // Class rows are probabilities already (exporter applied
        // sigmoid — verified). Mirror the Python verifier exactly: if
        // any score falls outside [0, 1], the rows are logits and the
        // whole matrix gets a sigmoid; otherwise scores pass through
        // untouched (a 0.8 stays 0.8 — no double-sigmoid).
        var inUnitInterval = true
        outer@ for (c in 0 until CLASS_COUNT) {
            for (i in 0 until CANDIDATE_COUNT) {
                val s = scoresRaw[c][i]
                if (!s.isFinite() || s < 0f || s > 1f) { inUnitInterval = false; break@outer }
            }
        }
        val scores: Array<FloatArray> = if (inUnitInterval) {
            scoresRaw
        } else {
            Array(CLASS_COUNT) { c ->
                FloatArray(CANDIDATE_COUNT) { i -> sigmoid(scoresRaw[c][i]) }
            }
        }

        val candidates = ArrayList<YoloDetection>(64)
        for (i in 0 until CANDIDATE_COUNT) {
            var bestClass = -1
            var bestConf = 0f
            for (c in 0 until CLASS_COUNT) {
                val s = scores[c][i]
                if (s > bestConf) { bestConf = s; bestClass = c }
            }
            if (bestClass < 0 || bestConf < CONF_THRESHOLD) continue
            val cx640 = xywh[0][i]
            val cy640 = xywh[1][i]
            val w640 = xywh[2][i]
            val h640 = xywh[3][i]
            if (!cx640.isFinite() || !cy640.isFinite() || !w640.isFinite() || !h640.isFinite()) continue
            if (w640 <= 0f || h640 <= 0f) continue
            // Inverse letterbox: subtract the pad offset, divide by
            // scale — then clip to the capture frame.
            val cx = (cx640 - geometry.padX) / geometry.scale
            val cy = (cy640 - geometry.padY) / geometry.scale
            val w = w640 / geometry.scale
            val h = h640 / geometry.scale
            val x1 = max(0f, cx - w / 2f)
            val y1 = max(0f, cy - h / 2f)
            val x2 = min(frameW.toFloat(), cx + w / 2f)
            val y2 = min(frameH.toFloat(), cy + h / 2f)
            if (x2 <= x1 || y2 <= y1) continue
            candidates.add(
                YoloDetection(
                    classId = bestClass,
                    confidence = bestConf,
                    centerX = (x1 + x2) / 2f,
                    centerY = (y1 + y2) / 2f,
                    width = x2 - x1,
                    height = y2 - y1
                )
            )
        }

        // Class-aware NMS, highest confidence first: a box is suppressed
        // only by a higher-confidence box of the SAME class. (The D2
        // stretch run showed why this matters: overlapping Keeper +
        // MyPlayer boxes on the same player must both survive the NMS
        // so the fusion gates — keeper conf >= 0.50, player merge —
        // decide, not the NMS.)
        candidates.sortByDescending { it.confidence }
        val kept = ArrayList<YoloDetection>(candidates.size)
        for (cand in candidates) {
            var suppressed = false
            for (k in kept) {
                if (k.classId == cand.classId && iou(k, cand) >= NMS_IOU) { suppressed = true; break }
            }
            if (!suppressed) kept.add(cand)
        }
        return kept
    }
}
