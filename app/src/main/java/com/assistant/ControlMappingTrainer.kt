package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass




import com.assistant.runtime.RuntimeFrame






import java.nio.ByteBuffer











import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*

import kotlin.math.abs



import kotlin.math.hypot






















object ControlMappingTrainer {

    enum class UiMode { UNKNOWN, SETTINGS, IN_MATCH }

    data class SlotDef(
        val id: Int,
        val nx: Float, val ny: Float,
        val attackLabel: String, val defendLabel: String,
        val dirAttack: Boolean, val dirDefend: Boolean
    )

    val SLOTS = listOf(
        SlotDef(1, 0.785f, 0.540f, "Through", "Switch", true, true),
        SlotDef(2, 0.892f, 0.504f, "Shoot (Clear)", "Tackle", true, false),
        SlotDef(3, 0.760f, 0.740f, "Pass", "Match-up", true, true),
        SlotDef(4, 0.879f, 0.704f, "Dash", "Dash & Pressure", false, true)
    )
    private const val JOY_NX = 0.182f
    private const val JOY_NY = 0.648f
    private const val TAB_NX = 0.500f
    private const val TAB_NY = 0.131f
    private const val BACK_NX = 0.157f
    private const val BACK_NY = 0.819f

    @Volatile var uiMode: UiMode = UiMode.UNKNOWN; private set
    val matchActive: Boolean get() = uiMode == UiMode.IN_MATCH

    private val slotX = FloatArray(5)
    private val slotY = FloatArray(5)
    private val slotHits = IntArray(5)
    init { for (s in SLOTS) { slotX[s.id] = s.nx; slotY[s.id] = s.ny } }

    private val observed = AtomicLong(0)
    private val settingsFrames = AtomicLong(0)
    private val matchFrames = AtomicLong(0)
    private val probesRead = AtomicLong(0)
    @Volatile private var lastMove = "none"

    // L11 (batch 1): maxDrag trainer probe mode (D9 delegated — the VALUE
    // can only be measured on Ed's device, so it is recorded as
    // measured-later: DispatchBounds.maxDragPx stays null until a probe
    // completes on-device; no number is invented here). The probe is
    // auto-armed: while armed it measures the joystick-base radius once
    // per second in-match (8 directions from the joystick centre, last
    // joystick-coloured pixel per direction; the minimum across
    // directions is the conservative radius), then disarms on the first
    // good measurement. After 10 failed attempts it gives up for the
    // session and the value stays unmeasured. Read-only pixel probing:
    // decision-neutral, no dispatch. setMaxDragProbeActive(false)
    // disables it (a future ModMenu toggle can call the same setter).
    @Volatile var maxDragProbeActive: Boolean = true; private set
    @Volatile private var measuredMaxDragPx: Float? = null
    private val maxDragProbeSamples = AtomicLong(0)
    private var maxDragProbeAttempts = 0
    private var lastMaxDragMeasureMs: Long = 0L

    fun setMaxDragProbeActive(active: Boolean) {
        maxDragProbeActive = active
    }

    /** The measured joystick drag radius in px, or null when unmeasured. */
    fun measuredMaxDragPx(): Float? = measuredMaxDragPx

    private fun measureMaxDrag(b: ByteBuffer, w: Int, h: Int, stride: Int, pixelStride: Int) {
        val now = System.currentTimeMillis()
        if (now - lastMaxDragMeasureMs < 1000L) return
        lastMaxDragMeasureMs = now
        val cx = JOY_NX * w
        val cy = JOY_NY * h
        val radii = FloatArray(8)
        var valid = 0
        for (d in 0 until 8) {
            val angle = d * Math.PI / 4.0
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()
            var radius = 0f
            var step = 4f
            while (step <= 260f) {
                val nx = (cx + dx * step) / w
                val ny = (cy + dy * step) / h
                if (nx !in 0f..1f || ny !in 0f..1f) break
                if (!probe(b, w, h, stride, nx, ny, pixelStride)) break
                if (!(isGray() || isGreen())) break
                radius = step
                step += 4f
            }
            if (radius > 0f) radii[valid++] = radius
        }
        val measured = MaxDragProbe.selectRadius(radii.copyOf(valid))
        if (measured != null) {
            measuredMaxDragPx = measured
            com.assistant.execution.DispatchBounds.setMaxDrag(measured)
            maxDragProbeSamples.incrementAndGet()
            maxDragProbeActive = false
            try {
                com.assistant.diagnostic.RuntimeLogger.log(
                    "MAXDRAG_PROBE measured=${measured.toInt()}px directions=$valid samples=${maxDragProbeSamples.get()} — probe disarmed, value in DispatchBounds.maxDragPx",
                    "TRAINER"
                )
            } catch (_: Throwable) { }
        } else {
            maxDragProbeAttempts++
            if (maxDragProbeAttempts >= 10) {
                maxDragProbeActive = false
                try {
                    com.assistant.diagnostic.RuntimeLogger.log(
                        "MAXDRAG_PROBE giving up after 10 attempts — joystick edge not found, value stays unmeasured",
                        "TRAINER"
                    )
                } catch (_: Throwable) { }
            }
        }
    }

    private val emaMs = FloatArray(8)
    private val emaN = IntArray(8)
    private val baseMs = floatArrayOf(30f, 30f, 30f, 30f, 30f, 30f, 30f, 30f)

    // D13: through-press STATE recording. The app cannot see a hardware
    // press (no touch coordinates reach it), so the observable signal
    // is the slot-1 centre pixel leaving its at-rest green while the
    // user is touching: the finger occludes the Through button (or the
    // game highlights it while held). Two consecutive occluded
    // in-match frames = a press CANDIDATE — an inference, logged as
    // such, never a confirmed press. Read by FrameAssembler
    // (frame.throughPressed) and the sanitizer gateway.
    @Volatile private var lastThroughPressMs = 0L
    @Volatile private var throughPressCandidateActive = false
    private var slot1OccludedFrames = 0
    private var lastThroughCandidateLogMs = 0L

    /** Wall time of the last through-press candidate, or 0 when none. */
    fun lastThroughPressMs(): Long = lastThroughPressMs

    /** True while a press candidate is fresh (within 250 ms). */
    fun throughPressCandidateFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        throughPressCandidateActive && nowMs - lastThroughPressMs <= 250L

    private fun recordThroughPressState(
        b: java.nio.ByteBuffer, w: Int, h: Int, stride: Int, pixelStride: Int
    ) {
        val now = System.currentTimeMillis()
        val slot1Green = probe(b, w, h, stride, slotX[1], slotY[1], pixelStride) && isGreen()
        if (!slot1Green && UserTouchState.isTouching()) slot1OccludedFrames++ else slot1OccludedFrames = 0
        if (slot1OccludedFrames >= 2) {
            throughPressCandidateActive = true
            lastThroughPressMs = now
            if (now - lastThroughCandidateLogMs >= 60_000L) {
                lastThroughCandidateLogMs = now
                try {
                    com.assistant.diagnostic.RuntimeLogger.log(
                        "THROUGH_PRESS_CANDIDATE slot=1 occludedFrames=$slot1OccludedFrames (inferred: finger occludes the Through button while touching; NOT a confirmed hardware press)",
                        "TRAINER"
                    )
                } catch (_: Throwable) { }
            }
        } else if (now - lastThroughPressMs > 250L) {
            throughPressCandidateActive = false
        }
    }
    @Volatile private var pendingClass = -1
    @Volatile private var pendingMs = 0L
    @Volatile private var pendingAt = 0L
    @Volatile private var baseSpeed = 0f

    fun trainedSlots(): Int = SLOTS.count { slotHits[it.id] >= 3 }

    fun isSlotTrained(id: Int): Boolean = id in 1..4 && slotHits[id] >= 3

    fun slotCenter(id: Int): Pair<Float, Float> = Pair(slotX[id], slotY[id])

    // SPLENDOR_CONTROL_MAP_V1 - the joystick origin was hard-coded at
    // (250,550) in RuntimeDecisionLoop.chooseRequest and as the GK default. The
    // trainer already probes the real joystick at (JOY_NX,JOY_NY); expose it so
    // dispatch uses the measured point.
    fun joystickCenterX(): Float = JOY_NX
    fun joystickCenterY(): Float = JOY_NY

    fun labelFor(id: Int, attacking: Boolean): String =
        SLOTS.firstOrNull { it.id == id }?.let { if (attacking) it.attackLabel else it.defendLabel } ?: "none"

    fun resolveMove(slotId: Int, attacking: Boolean, ms: Long, dx: Float, dy: Float): String {
        val s = SLOTS.firstOrNull { it.id == slotId } ?: return "none"
        val dirOk = if (attacking) s.dirAttack else s.defendLabel.let { s.dirDefend }
        val mag = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val flick = dirOk && mag >= 40f && ms <= 250L
        val hold = ms >= 120L
        val label = if (attacking) s.attackLabel else s.defendLabel
        val dir = if (flick) when {
            dy <= -0.5f * mag -> "_UP"
            dy >= 0.5f * mag -> "_DOWN"
            dx < 0 -> "_LEFT"
            else -> "_RIGHT"
        } else ""
        return when {
            flick -> label + "_FLICK" + dir
            hold && attacking && slotId == 2 -> "POWER_SHOT"
            hold && attacking && slotId == 3 -> "LOFTED_PASS"
            hold && attacking && slotId == 1 -> "LOFTED_THROUGH"
            hold && !attacking && slotId == 4 -> "PRESSURE_DASH_HOLD"
            hold && !attacking && slotId == 3 -> "MATCH_UP_HOLD"
            hold -> label + "_HOLD"
            else -> label + "_TAP"
        }
    }

    private fun px(b: ByteBuffer, idx: Int): Int = b.get(idx).toInt() and 0xFF

    private val probeBuffer = IntArray(3)
    private fun probe(b: ByteBuffer, w: Int, h: Int, stride: Int, nx: Float, ny: Float, pixelStride: Int): Boolean {
        val x = (nx * w).toInt(); val y = (ny * h).toInt()
        val idx = y.toLong() * stride + x.toLong() * pixelStride
        if (pixelStride < 3 || x !in 0 until w || y !in 0 until h || idx < 0 || idx + 2 >= b.limit()) return false
        val offset = idx.toInt()
        probesRead.incrementAndGet()
        probeBuffer[0] = px(b, offset); probeBuffer[1] = px(b, offset + 1); probeBuffer[2] = px(b, offset + 2)
        return true
    }

    private fun isGreen() = probeBuffer[1] > probeBuffer[0] + 8 && probeBuffer[1] > probeBuffer[2] + 8
    private fun isGray() = kotlin.math.abs(probeBuffer[0] - probeBuffer[1]) <= 14 && kotlin.math.abs(probeBuffer[1] - probeBuffer[2]) <= 14 && probeBuffer[0] in 110..215
    private fun isBlue() = probeBuffer[2] > probeBuffer[0] + 50 && probeBuffer[2] > 140

    fun observe(buffer: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int = 4) {
        if (w <= 0 || h <= 0) return
        observed.incrementAndGet()
        val tabGray = probe(buffer, w, h, rowStride, TAB_NX, TAB_NY, pixelStride) && isGray()
        val backBlue = probe(buffer, w, h, rowStride, BACK_NX, BACK_NY, pixelStride) && isBlue()
        var green = 0
        for (s in SLOTS) {
            if (probe(buffer, w, h, rowStride, slotX[s.id], slotY[s.id], pixelStride) && isGreen()) green++
        }
        val joyGray = probe(buffer, w, h, rowStride, JOY_NX, JOY_NY, pixelStride) && (isGray() || isGreen())
        val next = when {
            tabGray && backBlue -> UiMode.SETTINGS
            green >= 2 && joyGray -> UiMode.IN_MATCH
            else -> UiMode.UNKNOWN
        }
        uiMode = next
        if (next == UiMode.SETTINGS) {
            settingsFrames.incrementAndGet()
            for (s in SLOTS) {
                var bestNx = slotX[s.id]; var bestNy = slotY[s.id]; var bestScore = -1; var hit = false
                for (oy in -2..2 step 2) for (ox in -2..2 step 2) {
                    val nx = s.nx + ox * 0.01f; val ny = s.ny + oy * 0.01f
                    if (probe(buffer, w, h, rowStride, nx, ny, pixelStride) && isGreen()) {
                        val score = probeBuffer[1] - (probeBuffer[0] + probeBuffer[2]) / 2
                        if (score > bestScore) { bestScore = score; bestNx = nx; bestNy = ny; hit = true }
                    }
                }
                if (hit) {
                    slotX[s.id] = slotX[s.id] * 0.7f + bestNx * 0.3f
                    slotY[s.id] = slotY[s.id] * 0.7f + bestNy * 0.3f
                    slotHits[s.id]++
                }
            }
            lastMove = "settings-training"
        } else if (next == UiMode.IN_MATCH) {
            matchFrames.incrementAndGet()
            // L11 (batch 1): the maxDrag probe runs here, throttled to
            // once per second inside measureMaxDrag, and only while armed.
            if (maxDragProbeActive) measureMaxDrag(buffer, w, h, rowStride, pixelStride)
            // D13: record the through-press state every in-match frame
            // (slot-1 pixel + touch signal → press candidate).
            recordThroughPressState(buffer, w, h, rowStride, pixelStride)
        }
    }

    fun recordDispatch(phaseOrdinal: Int, durationMs: Long) {
        // D13: a THROUGH phase (ordinal 8) is the user's own press
        // sanitized by the gateway at a fixed 75 ms stroke — its
        // duration is not outcome-learned. Without this guard it would
        // coerce into bucket 7 (NONE) and train that bucket.
        if (phaseOrdinal == ActionClass.THROUGH.ordinal) {
            pendingClass = -1
            return
        }
        pendingClass = phaseOrdinal.coerceIn(0, 7)
        pendingMs = durationMs
        pendingAt = System.currentTimeMillis()
    }

    fun observeOutcome(frame: RuntimeFrame) {
        val mag = hypot(frame.ballVelocityX.toDouble(), frame.ballVelocityY.toDouble()).toFloat()
        baseSpeed = baseSpeed * 0.95f + mag * 0.05f
        if (pendingClass < 0) return
        val age = System.currentTimeMillis() - pendingAt
        if (age > 700L) { pendingClass = -1; return }
        val spike = frame.trusted && mag > baseSpeed * 2.5f + 2f
        if (spike) {
            val i = pendingClass
            emaMs[i] = if (emaN[i] == 0) pendingMs.toFloat() else emaMs[i] * 0.8f + pendingMs * 0.2f
            emaN[i]++
            lastMove = "trained:" + (ActionClass.values().getOrNull(i)?.name ?: i.toString()) + ":n=" + emaN[i] + ":ema=" + emaMs[i].toInt() + "ms"
            pendingClass = -1
        }
    }

    fun personalDuration(actionClass: ActionClass, hint: Long): Long {
        // D13: THROUGH has no outcome-learned bucket (see
        // recordDispatch) — the hint passes through unchanged.
        if (actionClass == ActionClass.THROUGH) return hint
        val i = actionClass.ordinal.coerceIn(0, 7)
        if (emaN[i] < 4) return hint
        val scale = (emaMs[i] / baseMs[i]).coerceIn(0.8f, 1.25f)
        return (hint * scale).toLong()
    }

    fun mappingRuntimeSnapshot(): Map<String, Any> = mapOf(
        "uiMode" to uiMode.name,
        "matchActive" to matchActive,
        "observedFrames" to observed.get(),
        "settingsFrames" to settingsFrames.get(),
        "matchFrames" to matchFrames.get(),
        "probesRead" to probesRead.get(),
        "trainedSlots" to trainedSlots(),
        // L11 (batch 1): the probe state — "unmeasured" until a probe
        // completes on-device; the value itself is measured-later.
        "maxDragPx" to (measuredMaxDragPx?.toString() ?: "unmeasured"),
        "maxDragProbeActive" to maxDragProbeActive,
        "maxDragProbeSamples" to maxDragProbeSamples.get(),
        // D13: the through-press candidate state.
        "throughPressCandidateActive" to throughPressCandidateActive,
        "lastThroughPressMs" to lastThroughPressMs,
        "lastMove" to lastMove
    )

    fun reset() {
        uiMode = UiMode.UNKNOWN
        for (s in SLOTS) { slotX[s.id] = s.nx; slotY[s.id] = s.ny; slotHits[s.id] = 0 }
        observed.set(0); settingsFrames.set(0); matchFrames.set(0); probesRead.set(0)
        for (i in 0..7) { emaMs[i] = 0f; emaN[i] = 0 }
        pendingClass = -1; lastMove = "none"; baseSpeed = 0f
        // L11: a reset re-arms the probe and clears the measurement (the
        // probe must re-measure; a stale radius must not survive a reset).
        measuredMaxDragPx = null
        maxDragProbeSamples.set(0)
        maxDragProbeAttempts = 0
        lastMaxDragMeasureMs = 0L
        maxDragProbeActive = true
        // D13: a reset clears the press candidate (a stale candidate
        // must not survive a reset and sanitize a later press).
        lastThroughPressMs = 0L
        throughPressCandidateActive = false
        slot1OccludedFrames = 0
        lastThroughCandidateLogMs = 0L
        com.assistant.execution.DispatchBounds.setMaxDrag(null)
    }
}

/**
 * L11 (batch 1): the pure radius selection for the maxDrag probe.
 * The measured radius is the MINIMUM across valid directions — the
 * tightest direction binds the drag. Fewer than 4 valid directions is
 * not a measurement (null), never a guess from one ray.
 */
object MaxDragProbe {
    const val MIN_DIRECTIONS = 4

    fun selectRadius(directionRadii: FloatArray): Float? {
        val valid = directionRadii.filter { it > 0f && it.isFinite() }
        if (valid.size < MIN_DIRECTIONS) return null
        return valid.minOrNull()
    }
}

/* ========
Phase3WorldState
======== */
