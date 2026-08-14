package com.assistant.adapter.smartassist

import com.assistant.admin.AdminConfigStore

/**
 * GAP 2 + GAP 3 — VISION TRUST
 *
 * GAP 1C  capture gating: only frames from the game are trusted at all.
 * GAP 2   age decay:      a sighting loses value as it gets old.
 * GAP 3   lane confidence: derived from trust + defender spread + motion stability.
 *
 * Self-contained on purpose. Nothing here reaches into an engine, so any engine
 * may read it without creating a dependency, and it can be nano-upgraded alone.
 *
 * V3 (Task B): every gate answers the admin store live (defaults = the old
 * hard-coded values). These gates decide whether the ENTIRE contributor
 * stack is allowed to act - on a slower device the fixed 180ms latency
 * ceiling and 400ms sighting window were chronically rejecting honest
 * frames with no way to tune them without a rebuild.
 */
object VisionTrust {

    // ---------------- tunables (ADMIN-TUNABLE, defaults = original values) ----------------
    // PHASE4: 15fps tuning — FRESH_MS=120ms = 1.8 frames → ball always seems stale at 15fps
    // → frameTrusted() returns false every other frame → ALL contributors blocked
    // Fix: FRESH_MS=200ms (3 frames), STALE_MS=600ms (9 frames) for 15fps operation
    private val FRESH_MS: Long get() = AdminConfigStore.getLong("assist.trust.fresh_ms", 200L)
    private val STALE_MS: Long get() = AdminConfigStore.getLong("assist.trust.stale_ms", 600L)
    private val LATENCY_LIMIT_MS: Float get() = AdminConfigStore.get("assist.trust.latency_ms", 180f)
    private val TRUST_FLOOR: Float get() = AdminConfigStore.get("assist.trust.floor", 0.55f)
    private val LANE_FLOOR: Float get() = AdminConfigStore.get("assist.trust.lane_floor", 0.35f)

    /** a real match cannot contain more than this many tracked entities */
    private val SANE_ENTITY_MAX: Int get() = AdminConfigStore.getInt("assist.trust.entity_max", 30)

    // ---------------- gap 1c: is the game actually on screen ----------------
    @Volatile private var foregroundIsGame = false
    @Volatile private var lastForegroundPkg = ""
    @Volatile private var gatedFrames = 0L

    @JvmStatic
    fun onForegroundPackage(pkg: String?, gamePkgs: Set<String>) {
        val p = pkg ?: ""
        lastForegroundPkg = p
        if (p.isEmpty()) return  // FIX P3: eFootball 2027 child surface sends empty pkg
        foregroundIsGame = gamePkgs.any { p.contains(it, true) }
    }

    /** explicit override for callers that already resolved the decision */
    @JvmStatic
    fun setGameForeground(isGame: Boolean) { foregroundIsGame = isGame }

    @JvmStatic
    fun isGameForeground(): Boolean = foregroundIsGame

    /** true when this frame must not be ingested at all */
    @JvmStatic
    fun shouldGateFrame(): Boolean {
        if (!foregroundIsGame) { gatedFrames++; return true }
        return false
    }

    // ---------------- gap 2: age-decayed ball trust ----------------
    @Volatile private var lastBallStampMs = 0L
    @Volatile private var lastBallConfidence = 0f
    @Volatile private var lastLatencyMs = 0f
    @Volatile private var insaneRejects = 0L

    @JvmStatic
    fun stampBall(confidence: Float, nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        lastBallStampMs = nowMs
        lastBallConfidence = confidence.coerceIn(0f, 1f)
    }

    @JvmStatic
    fun stampLatency(ms: Float) { lastLatencyMs = ms }

    /** confidence faded by how long ago we actually saw the ball */
    @JvmStatic
    @JvmOverloads
    fun ballTrust(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Float {
        if (lastBallStampMs == 0L) return 0f
        val age = nowMs - lastBallStampMs
        val fresh = FRESH_MS
        val stale = STALE_MS
        val span = (stale - fresh).coerceAtLeast(1L)
        val decay = when {
            age <= fresh -> 1f
            age >= stale -> 0f
            else -> 1f - (age - fresh).toFloat() / span.toFloat()
        }
        return (lastBallConfidence * decay).coerceIn(0f, 1f)
    }

    /**
     * Entity-count sanity. A frame claiming 100 players is reading UI, not a pitch.
     * Returns false and counts a reject so the log shows how often it fires.
     */
    @JvmStatic
    fun entityCountSane(players: Int, opponents: Int): Boolean {
        val max = SANE_ENTITY_MAX
        if (players > max || opponents > max) {
            insaneRejects++
            return false
        }
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun frameTrusted(players: Int = 0, opponents: Int = 0): Boolean {
        if (!foregroundIsGame) return false
        if (players > 0 || opponents > 0) {
            if (!entityCountSane(players, opponents)) return false
        }
        if (lastLatencyMs > LATENCY_LIMIT_MS) return false
        return ballTrust() >= TRUST_FLOOR
    }

    // ---------------- gap 3: motion stability ----------------
    private val vx = FloatArray(3)
    private val vy = FloatArray(3)
    @Volatile private var vIdx = 0
    @Volatile private var vCount = 0

    @JvmStatic
    fun pushMotion(dx: Float, dy: Float) {
        synchronized(vx) {
            vx[vIdx] = dx; vy[vIdx] = dy
            vIdx = (vIdx + 1) % 3
            if (vCount < 3) vCount++
        }
    }

    /** 0 = jittering noise, 1 = steady consistent travel */
    @JvmStatic
    fun directionStability(): Float {
        if (vCount < 3) return 0f
        var acc = 0f; var pairs = 0
        synchronized(vx) {
            for (i in 0 until 3) {
                val j = (i + 1) % 3
                val m1 = Math.sqrt((vx[i]*vx[i] + vy[i]*vy[i]).toDouble()).toFloat()
                val m2 = Math.sqrt((vx[j]*vx[j] + vy[j]*vy[j]).toDouble()).toFloat()
                if (m1 < 0.0001f || m2 < 0.0001f) continue
                val cos = ((vx[i]*vx[j] + vy[i]*vy[j]) / (m1 * m2)).coerceIn(-1f, 1f)
                acc += (cos + 1f) / 2f
                pairs++
            }
        }
        return if (pairs == 0) 0f else (acc / pairs).coerceIn(0f, 1f)
    }

    // ---------------- gap 3: lane confidence ----------------
    @Volatile private var laneSpread = 0f

    /** 0 = defenders packed (closed), 1 = spread wide (open) */
    @JvmStatic
    fun stampLaneSpread(spread: Float) { laneSpread = spread.coerceIn(0f, 1f) }

    @JvmStatic
    fun laneConfidence(): Float {
        val trust = ballTrust()
        if (trust < LANE_FLOOR) return 0f
        return (trust * 0.4f + laneSpread * 0.3f + directionStability() * 0.3f)
            .coerceIn(0f, 1f)
    }

    // ---------------- proof surface ----------------
    private val ticks = java.util.concurrent.atomic.AtomicLong(0L)

    @JvmStatic
    fun diagnostics(): String =
        "fg=" + foregroundIsGame + " pkg=" + lastForegroundPkg +
        " gated=" + gatedFrames +
        " ballTrust=" + String.format("%.3f", ballTrust()) +
        " lane=" + String.format("%.3f", laneConfidence()) +
        " stability=" + String.format("%.3f", directionStability()) +
        " spread=" + String.format("%.3f", laneSpread) +
        " latency=" + lastLatencyMs +
        " insaneRejects=" + insaneRejects

    @JvmStatic
    @JvmOverloads
    fun tickAndLog(every: Long = 20L) {
        if (ticks.incrementAndGet() % every != 0L) return
        try { com.assistant.diagnostic.RuntimeLogger.log(diagnostics(), "VISIONTRUST") } catch (_: Throwable) { }
    }
}
