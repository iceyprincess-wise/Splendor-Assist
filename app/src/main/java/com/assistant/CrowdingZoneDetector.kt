package com.assistant









import android.content.Context

import android.content.SharedPreferences















import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger









import com.assistant.runtime.*





import com.assistant.runtime.RuntimeFrame


















import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*




























object CrowdingZoneDetector {

    private const val DENSITY_THRESHOLD = 0.75f
    private const val CONFIDENCE_THRESHOLD = 0.45f
    private const val DENSITY_WEIGHT = 0.60f
    private const val INV_CONF_WEIGHT = 0.40f

    private val detections = AtomicLong(0L)

    // No68-C-D6: last zone value mirrored cross-process. The registry write
    // is change-only (a file write per frame would be I/O on the decision
    // path); LagVerdictEngine polls the registry from :survival, where the
    // in-process bus copy of this zone is always false.
    @Volatile private var lastRegistryZone: Boolean? = null

    private fun publishZone(zone: Boolean, level: Float) {
        AdapterSignalBus.publishCrowdingZone(zone, level)
        if (lastRegistryZone != zone) {
            lastRegistryZone = zone
            try { com.assistant.diagnostic.registry.PerformanceTelemetryRegistry.publishCrowdingZone(zone) } catch (_: Throwable) {}
        }
    }

    @Volatile var inCrowdedZone: Boolean = false; private set
    @Volatile var crowdingLevel: Float = 0f; private set
    @Volatile private var enabled = true
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        try { prefs?.edit()?.putBoolean("crowding_enabled", value)?.apply() } catch (_: Throwable) {}
        if (!value) reset()
    }

    fun isEnabled(): Boolean = enabled

    fun init(context: android.content.Context) {
        try {
            val p = context.applicationContext.getSharedPreferences("splendor_engine_toggles", 0)
            prefs = p
            enabled = p.getBoolean("crowding_enabled", true)
        } catch (_: Throwable) {}
    }

    /**
     * Evaluate crowding from the current frame.
     * Returns true when in a penalty-box / corner crowded zone.
     *
     * No68-C-D7 (call-site truth, correcting the misplaced KDoc that used
     * to sit on this engine): this is NOT called once per frame.
     * RuntimeDecisionLoop calls evaluate() only after arbitration
     * (chooseRequest), and only on frames that produced a request
     * (best/parked non-null) — untrusted frames and no-contribution frames
     * never reach it. It DOES run before the amplifiers (CaptaincySkill
     * amplification + crowding saturation cap, RuntimeDecisionLoop:222-241),
     * so the old KDoc's "BEFORE amplifiers" clause was its one true part —
     * what was false was "once per frame" and the implied pre-arbitration
     * position. (No68-C-D41: the first D7 wording said "NOT before the
     * amplifiers"; that overcorrected — see RuntimeDecisionLoop:216.)
     * What IS per-frame is the bus publish below, which
     * runs on every call including the early returns, so the bus never
     * holds a stale true. The LagVerdictEngine "+2 polls" protection is
     * real only since No68-C-D6, which mirrors the zone cross-process.
     */
    fun evaluate(frame: RuntimeFrame): Boolean {
        if (!enabled) {
            inCrowdedZone = false
            crowdingLevel = 0f
            publishZone(false, 0f)
            return false
        }
        if (!frame.trusted) {
            inCrowdedZone = false
            crowdingLevel = 0f
            publishZone(false, 0f)
            return false
        }

        val density = frame.defenderDensity.coerceIn(0f, 1f)
        val invConf = (1f - frame.confidence.coerceIn(0f, 1f))
        val level = (density * DENSITY_WEIGHT + invConf * INV_CONF_WEIGHT)
            .coerceIn(0f, 1f)

        val zone =
            density > DENSITY_THRESHOLD &&
                frame.confidence < CONFIDENCE_THRESHOLD

        inCrowdedZone = zone
        crowdingLevel = level
        publishZone(zone, level)

        if (zone) {
            val count = detections.incrementAndGet()
            if (count % 60L == 0L) {
                RuntimeLogger.log(
                    "CROWDING_ZONE: density=%.2f confidence=%.2f level=%.2f #%d"
                        .format(density, frame.confidence, level, count),
                    "CROWDING"
                )
            }
        }

        return zone
    }

    fun reset() {
        inCrowdedZone = false
        crowdingLevel = 0f
        detections.set(0L)
        publishZone(false, 0f)
    }

    fun diagnostics(): Map<String, Any> = mapOf(
        "inCrowdedZone" to inCrowdedZone,
        "crowdingLevel"  to crowdingLevel,
        "detections"     to detections.get(),
        "enabled" to enabled
    )
}

/* ========
DecisionResult
======== */
