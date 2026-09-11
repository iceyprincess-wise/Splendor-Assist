package com.assistant.adapter.smartassist

import com.assistant.runtime.ActionClass
import com.assistant.runtime.RuntimeFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot

/**
 * ControlMappingTrainer - button-mapping ground truth + personal timing trainer.
 * Trained from the user's mapping screenshots (Attacking/Defending), stored
 * normalized so any capture resolution works. Probe-only: <=43 byte reads per
 * frame, never a full-frame pass, so the 60fps hot path is untouched.
 */
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

    private val emaMs = FloatArray(8)
    private val emaN = IntArray(8)
    private val baseMs = floatArrayOf(30f, 30f, 30f, 30f, 30f, 30f, 30f, 30f)
    @Volatile private var pendingClass = -1
    @Volatile private var pendingMs = 0L
    @Volatile private var pendingAt = 0L
    @Volatile private var baseSpeed = 0f

    fun trainedSlots(): Int = SLOTS.count { slotHits[it.id] >= 3 }

    fun slotCenter(id: Int): Pair<Float, Float> = Pair(slotX[id], slotY[id])

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

    private fun probe(b: ByteBuffer, w: Int, h: Int, stride: Int, nx: Float, ny: Float): IntArray? {
        val x = (nx * w).toInt(); val y = (ny * h).toInt()
        val idx = y * stride + x * 4
        if (idx < 0 || idx + 2 >= b.capacity()) return null
        probesRead.incrementAndGet()
        return intArrayOf(px(b, idx), px(b, idx + 1), px(b, idx + 2))
    }

    private fun isGreen(c: IntArray) = c[1] > c[0] + 8 && c[1] > c[2] + 8
    private fun isGray(c: IntArray) = kotlin.math.abs(c[0] - c[1]) <= 14 && kotlin.math.abs(c[1] - c[2]) <= 14 && c[0] in 110..215
    private fun isBlue(c: IntArray) = c[2] > c[0] + 50 && c[2] > 140

    fun observe(buffer: ByteBuffer, w: Int, h: Int, rowStride: Int) {
        if (w <= 0 || h <= 0) return
        observed.incrementAndGet()
        val tab = probe(buffer, w, h, rowStride, TAB_NX, TAB_NY)
        val back = probe(buffer, w, h, rowStride, BACK_NX, BACK_NY)
        val tabGray = tab != null && isGray(tab)
        val backBlue = back != null && isBlue(back)
        var green = 0
        for (s in SLOTS) {
            val c = probe(buffer, w, h, rowStride, slotX[s.id], slotY[s.id])
            if (c != null && isGreen(c)) green++
        }
        val joy = probe(buffer, w, h, rowStride, JOY_NX, JOY_NY)
        val joyGray = joy != null && (isGray(joy) || isGreen(joy))
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
                for (oy in intArrayOf(-2, 0, 2)) for (ox in intArrayOf(-2, 0, 2)) {
                    val nx = s.nx + ox * 0.01f; val ny = s.ny + oy * 0.01f
                    val c = probe(buffer, w, h, rowStride, nx, ny) ?: continue
                    val score = c[1] - (c[0] + c[2]) / 2
                    if (isGreen(c) && score > bestScore) { bestScore = score; bestNx = nx; bestNy = ny; hit = true }
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
        }
    }

    fun recordDispatch(phaseOrdinal: Int, durationMs: Long) {
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
        val spike = mag > baseSpeed * 2.5f + 2f || frame.goalDetected
        if (spike) {
            val i = pendingClass
            emaMs[i] = if (emaN[i] == 0) pendingMs.toFloat() else emaMs[i] * 0.8f + pendingMs * 0.2f
            emaN[i]++
            lastMove = "trained:" + (ActionClass.values().getOrNull(i)?.name ?: i.toString()) + ":n=" + emaN[i] + ":ema=" + emaMs[i].toInt() + "ms"
            pendingClass = -1
        }
    }

    fun personalDuration(actionClass: ActionClass, hint: Long): Long {
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
        "lastMove" to lastMove
    )

    fun reset() {
        uiMode = UiMode.UNKNOWN
        for (s in SLOTS) { slotX[s.id] = s.nx; slotY[s.id] = s.ny; slotHits[s.id] = 0 }
        observed.set(0); settingsFrames.set(0); matchFrames.set(0); probesRead.set(0)
        for (i in 0..7) { emaMs[i] = 0f; emaN[i] = 0 }
        pendingClass = -1; lastMove = "none"; baseSpeed = 0f
    }
}
