package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * Batch 6b — Body-Weight Press (Ed's live report: "ball passing in
 * between player legs, they dont use there full body to weigh
 * opponent players off balance aggressive", part 2).
 *
 * Gap PROVEN (PLAN.md trace): no sustained full-body-weight pressure
 * exists. PhysicalBargeEngine (G-M) and BeastTackleEngine (G-N) are
 * single-shot MOMENTS (<= 20 ms): a barge swipe, a tackle offer.
 * Both require the opponent to HAVE the ball and the ball
 * carrier-paced — and both fire once and are gone. Nothing in the
 * repo holds a lean/shoulder against a shielding carrier over
 * frames to unbalance him.
 *
 * What this engine does: when the Beast window is open (same
 * foul-risk gates, same D14 guard — reused, not re-invented) AND the
 * presser is inside CONTACT distance of the carrier (the barge
 * contact radius, 6% of capture height — bodies can touch), it
 * counts consecutive frames of contact. The offer opens only after
 * SUSTAINED_FRAMES (3) frames with the SAME presser in contact —
 * a passing brush never qualifies — and stays open, re-offered every
 * frame, while contact holds. That re-offer IS the sustained state:
 * the terminal renders each frame's offer (duration hint 85 ms, the
 * most RuntimeDecisionLoop honours — its duration clamp is
 * [15, 85] ms), so the controlled defender keeps leaning into the
 * carrier instead of firing one swipe and releasing.
 *
 * Gates (all reused from the engines Ed already approved — no
 * retune, no new thresholds of our own):
 * - BeastTackleEngine.window(frame) must be open: opponent has the
 *   ball, carrier-paced (<= 150 px/s), not from behind (dot <= 0.5),
 *   not last man, not in our own box, carrier ahead of the tight
 *   line (D14 guard). Any closed reason closes this window too.
 * - PhysicalBargeEngine.CONTACT_RADIUS_FRACTION_OF_HEIGHT (0.06):
 *   presser inside contact distance of the ball/carrier.
 * - SUSTAINED_FRAMES = 3: contact must hold 3 consecutive frames
 *   with the same presser. At the 20 fps processed rate that is
 *   ~150 ms of real contact before the lean starts — a brush (1-2
 *   frames) never opens it.
 *
 * Output is a DEFEND-class offer at authority 0.80 (below Beast's
 * 0.90 hard tackle — a lean is not a tackle) with duration hint
 * 85 ms, target on the carrier. When the window closes the
 * contributor is silent and the ordinary press / Beast owns the
 * frame. Only the controlled defender (the presser) moves — NO
 * second-player control (G-O block stands).
 *
 * State: leanFrames + lastPresserId live on this object and are
 * reset by reset() (called from the contributor's reset(), which
 * the registry calls via resetAll()). A presser change restarts
 * the count at 1 — the new presser must earn his own 3 frames.
 */
data class BodyWeightWindow(
    val open: Boolean,
    /** Why the gate closed, for the log / pins. Empty when open. */
    val closedReason: String,
    val presserId: Int?,
    /** Consecutive contact frames with this presser (0 when closed). */
    val leanFrames: Int
)

object BodyWeightPressEngine {

    /** Contact must hold this many consecutive frames to count as sustained. */
    const val SUSTAINED_FRAMES = 3
    /** Duration hint: the most RuntimeDecisionLoop honours ([15, 85] ms). */
    const val LEAN_DURATION_HINT_MS = 85L

    @Volatile private var leanFrames = 0
    @Volatile private var lastPresserId: Int? = null

    fun reset() {
        leanFrames = 0
        lastPresserId = null
    }

    fun window(frame: RuntimeFrame): BodyWeightWindow {
        val closed = { reason: String ->
            leanFrames = 0
            lastPresserId = null
            BodyWeightWindow(false, reason, null, 0)
        }
        if (!frame.trusted || frame.hasBall) return closed("no-ball-or-ours")
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return closed("no-capture")
        // Foul-risk gates + D14 guard: Beast's window, reused whole.
        val beast = BeastTackleEngine.window(frame)
        if (!beast.open) return closed("beast-closed:" + beast.closedReason)
        val presserId = beast.presserId ?: return closed("no-presser")
        // Contact: bodies can touch (barge contact radius, Ed-set).
        val radius = PhysicalBargeEngine.CONTACT_RADIUS_FRACTION_OF_HEIGHT * frame.captureHeight
        val presser = frame.entities.firstOrNull { it.id == presserId }
            ?: return closed("presser-gone")
        val dist = hypot(presser.x - frame.ballX, presser.y - frame.ballY)
        if (dist > radius) return closed("out-of-contact")
        // Sustained: same presser, consecutive frames.
        if (lastPresserId == presserId) leanFrames++ else {
            leanFrames = 1
            lastPresserId = presserId
        }
        if (leanFrames < SUSTAINED_FRAMES) {
            return BodyWeightWindow(false, "building:$leanFrames", presserId, leanFrames)
        }
        return BodyWeightWindow(true, "", presserId, leanFrames)
    }
}
