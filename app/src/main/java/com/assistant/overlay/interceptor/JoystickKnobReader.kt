package com.assistant.overlay.interceptor

import com.assistant.execution.DispatchBounds
import com.assistant.runtime.UserIntentDirection
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * JoystickKnobReader — reads the joystick knob's deflection (angle +
 * magnitude) from the live frame and publishes it as the user's
 * intended direction, so the RDL never dispatches against the user's
 * held direction.
 *
 * OCCLUSION — RESOLVED (2026-11-24, from code + evidence, not
 * assumed): UserIntentVision's KDoc claimed "while the user holds the
 * joystick their finger occludes the knob, so the knob's position is
 * not pixel-readable from the frame". That premise is wrong for this
 * capture: MediaProjection records the RENDERED frame — a physical
 * finger never appears in it. The game renders the knob at the
 * deflected position (the player sees it move), and the code already
 * treats the knob pixels as visible: ControlMappingTrainer:441 reads
 * grey-or-green at the joystick point to decide IN_MATCH, and
 * UserIntentVision's own joystick-hold inference reads the centre
 * pixel LEAVING grey-or-green while touching — which only happens
 * because the knob MOVED off-centre, not because a finger covers it.
 * The knob IS pixel-readable when held.
 *
 * DEFLECTED — MEASURED (2026-11-24, paused YouTube frames of the
 * BZG match video b4aQ8jX22mE, 1919x998 screenshots; Apify failed
 * 3x — YouTube blocks its proxy IPs — so no PNG files exist and
 * these numbers are the deliverable): 7:36 knob UP-RIGHT, centre
 * (571,654), centre pixel (248,244,242), 11x11 patch
 * (241.3,237.9,228.4); 7:53 knob DOWN-RIGHT, centre (635,648),
 * centre pixel (243,243,243), 11x11 patch (244.9,242.9,242.8). The
 * shade DOES change when deflected: the centre carries a near-white
 * highlight (~241-245 vs rest 207) and the body sits ~217 with a
 * slight green cast (worst pairwise mean |G-B| ~11.5, vs TOL 14).
 * Every measured region mean passes the segmentation test (R
 * 140..255, pairwise spreads <= 11.5) — and the test is per-pixel
 * pairwise, so the "spread ~13" figure (max-minus-min across
 * channels) is not the test this reader applies. The 40% match gate
 * absorbs per-pixel scatter, and live frames are uncompressed
 * MediaProjection, cleaner than these YouTube-derived numbers. No
 * constants changed: the as-built test detects the deflected knob.
 * What remains UNPROVEN: whether the game renders an extra touch
 * indicator when held (none seen in either frame). Hence the
 * confidence gate below and fail-open to the hold inference whenever
 * the knob cannot be segmented with confidence — a missing reading
 * means "no direction known", never a guessed direction.
 *
 * SEGMENTATION (measured ground truth, parent-measured 2026-11-24
 * from Ed's screenshots 837488_2_wpqw.jpg + 837487_3_mq0b.jpg, both
 * 1600x720 — do NOT re-measure, do NOT guess): knob (207,207,207)
 * neutral grey, disc average r<=15 (207,207,207), range 204-213,
 * R=G=B; radial profile stays 185-215 to r=50, drops to black (0,0,0)
 * at r=60 — disc radius ~55-60 source px; background around the knob
 * is black (0,0,0). Rule: a pixel is knob-grey when its red channel
 * is >= KNOB_GREY_MIN (140, the parent's segmentation floor — the
 * knob is 204-213, the black background is 0) and achromatic
 * (|R-G| and |G-B| <= 14, the trainer's isGray tolerance). The upper
 * bound is 255: the knob's bright inner highlight is achromatic too,
 * and a hole in the middle of the disc does not move its centroid.
 *
 * GEOMETRY: no hardcoded coordinates. The centre is the measured
 * joystick centre (ButtonMappingEngine, via the caller's
 * joystickReadCenter — the same point UserIntentVision reads for the
 * hold). The knob radius in capture px is KNOB_RADIUS_SOURCE_PX
 * (57f, the midpoint of the measured 55-60) scaled by
 * min(w,h)/720f — the source frame is 1600x720, so the short side is
 * always the 720 axis in either orientation. The scan window is
 * centre +/- (knob radius + max deflection); the max deflection is
 * the measured DispatchBounds.maxDragPx, falling back to
 * min(w,h) x 0.08f while unmeasured (the same fallback RDL's
 * analogDeflectionRadius uses). NOTE on the parent's "~10 px at 288
 * capture": that figure is for a 288-wide downscale; at the 640x288
 * in-play capture the knob radius is ~23 px. This engine computes
 * radii from the live frame dims, so it is correct at either scale.
 *
 * CENTROID: the mean of the matching pixels is the disc centre; its
 * offset from the rest centre is the deflection vector. Angle =
 * atan2(dy, dx) in screen coords (x right, y down). Magnitude =
 * |offset| / maxDeflectionPx, clamped 0..1. A clipped disc (knob at
 * the window edge) biases the centroid INWARD, so magnitude is
 * under-reported, never over-reported — the safe direction.
 *
 * CONFIDENCE: matched samples vs the expected disc area at the scan
 * step (pi x knobRadiusPx^2 / step^2). A reading is published only
 * when confidence >= MIN_MATCH_FRACTION (0.4) AND magnitude >=
 * MIN_DIRECTION_MAGNITUDE (0.15, ~3.5 px at 640x288 — above rest
 * jitter). Below either, null: at rest, occluded, or unsegmentable.
 * The scan reads the ByteBuffer directly (probePixel allocates an
 * IntArray per call — ~2k reads/frame would churn); the index math is
 * the trainer's probe() math (idx = y*stride + x*pixelStride).
 *
 * CALLER: UserIntentVision.observeFrame, on the same capture buffer,
 * right after the TouchSourceDiscriminator skip — our own injected
 * gesture renders the joystick deflected identically to a real finger,
 * so the reader must not run during our own dispatch (A3
 * self-suppression: reading our own direction would suppress our own
 * next request). Pure, stateless, no side effects.
 */
object JoystickKnobReader {

    /** Parent's segmentation floor: knob grey is 204-213, background is 0. */
    const val KNOB_GREY_MIN = 140

    /** Achromatic tolerance — the trainer's isGray tolerance. */
    private const val KNOB_ACHROMATIC_TOL = 14

    /** Midpoint of the measured knob radius, 55-60 source px. */
    private const val KNOB_RADIUS_SOURCE_PX = 57f

    /** Publish only when >= 40% of the expected disc samples match. */
    private const val MIN_MATCH_FRACTION = 0.4f

    /** Deadzone: below this magnitude the stick is at rest / jitter. */
    const val MIN_DIRECTION_MAGNITUDE = 0.15f

    /** Absolute minimum matched samples, whatever the frame size. */
    private const val MIN_MATCH_SAMPLES = 40

    /** Scan step in capture px (2 = ~4x fewer reads, centroid unaffected). */
    private const val SCAN_STEP_PX = 2

    private fun px(b: ByteBuffer, idx: Int): Int = b.get(idx).toInt() and 0xFF

    /**
     * Reads the knob deflection from the frame. centreX/centreY are
     * the joystick rest centre in capture px (the caller's
     * joystickReadCenter). Returns null when no confident direction
     * can be read (at rest, occluded, unsegmentable) — never a guess.
     */
    fun read(
        b: ByteBuffer,
        w: Int,
        h: Int,
        stride: Int,
        pixelStride: Int,
        centreX: Float,
        centreY: Float
    ): UserIntentDirection? {
        if (w <= 0 || h <= 0 || pixelStride < 3) return null
        val scale = minOf(w, h) / 720f
        if (!scale.isFinite() || scale <= 0f) return null
        val knobRadiusPx = KNOB_RADIUS_SOURCE_PX * scale
        val maxDeflectPx = DispatchBounds.maxDragPx ?: (minOf(w, h) * 0.08f)
        if (!maxDeflectPx.isFinite() || maxDeflectPx <= 0f) return null
        val windowR = knobRadiusPx + maxDeflectPx
        val x0 = maxOf(0, (centreX - windowR).toInt())
        val x1 = minOf(w - 1, (centreX + windowR).toInt())
        val y0 = maxOf(0, (centreY - windowR).toInt())
        val y1 = minOf(h - 1, (centreY + windowR).toInt())
        if (x1 < x0 || y1 < y0) return null
        var count = 0
        var sumX = 0L
        var sumY = 0L
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val idx = y.toLong() * stride + x.toLong() * pixelStride
                if (idx >= 0 && idx + 2 < b.limit()) {
                    val off = idx.toInt()
                    val r = px(b, off)
                    val g = px(b, off + 1)
                    val bl = px(b, off + 2)
                    if (r in KNOB_GREY_MIN..255 && abs(r - g) <= KNOB_ACHROMATIC_TOL && abs(g - bl) <= KNOB_ACHROMATIC_TOL) {
                        count++
                        sumX += x
                        sumY += y
                    }
                }
                x += SCAN_STEP_PX
            }
            y += SCAN_STEP_PX
        }
        // Expected disc samples at the scan step; confidence is the
        // matched fraction of the disc. A partial disc (knob clipped
        // by the window edge) scores low and fails the gate.
        val stepArea = (SCAN_STEP_PX * SCAN_STEP_PX).toFloat()
        val expected = (PI.toFloat() * knobRadiusPx * knobRadiusPx / stepArea)
        if (count < MIN_MATCH_SAMPLES || expected <= 0f) return null
        val confidence = (count.toFloat() / expected).coerceIn(0f, 1f)
        if (confidence < MIN_MATCH_FRACTION) return null
        val centroidX = sumX.toFloat() / count
        val centroidY = sumY.toFloat() / count
        val dx = centroidX - centreX
        val dy = centroidY - centreY
        val dist = hypot(dx, dy)
        if (!dist.isFinite() || dist <= 0f) return null
        val magnitude = (dist / maxDeflectPx).coerceIn(0f, 1f)
        if (magnitude < MIN_DIRECTION_MAGNITUDE) return null
        return UserIntentDirection(
            dirX = dx / dist,
            dirY = dy / dist,
            magnitude = magnitude,
            confidence = confidence
        )
    }
}
