package com.assistant.adapter.smartassist

import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.RuntimeFrame
import java.util.concurrent.atomic.AtomicLong

/**
 * CrowdingZoneDetector
 *
 * Detects penalty-box and corner-kick scenarios where defenderDensity
 * peaks near 1.0 and confidence collapses simultaneously. In these
 * situations every upstream gameplay engine (FightingSpirit, Captaincy,
 * MagneticFeet) peaks at once because they share the same frame signals.
 * Device rendering load also spikes (many player models) causing real
 * FPS stalls that LagVerdictEngine cannot distinguish from device stress.
 *
 * This engine publishes to AdapterSignalBus so:
 *  - RuntimeDecisionLoop applies a duration saturation cap (45ms max).
 *  - LagVerdictEngine requires 2 extra confirmation polls before CHOKING.
 *
 * Pure detection: no gameplay authority, no bus writes except crowdingZone.
 * Called once per frame by RuntimeDecisionLoop BEFORE amplifiers.
 */
object CrowdingZoneDetector {

    private const val DENSITY_THRESHOLD = 0.75f
    private const val CONFIDENCE_THRESHOLD = 0.45f
    private const val DENSITY_WEIGHT = 0.60f
    private const val INV_CONF_WEIGHT = 0.40f

    private val detections = AtomicLong(0L)

    @Volatile var inCrowdedZone: Boolean = false; private set
    @Volatile var crowdingLevel: Float = 0f; private set

    /**
     * Evaluate crowding from the current frame.
     * Returns true when in a penalty-box / corner crowded zone.
     */
    fun evaluate(frame: RuntimeFrame): Boolean {
        if (!frame.trusted) {
            inCrowdedZone = false
            crowdingLevel = 0f
            AdapterSignalBus.publishCrowdingZone(false, 0f)
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
        AdapterSignalBus.publishCrowdingZone(zone, level)

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
        AdapterSignalBus.publishCrowdingZone(false, 0f)
    }

    fun diagnostics(): Map<String, Any> = mapOf(
        "inCrowdedZone" to inCrowdedZone,
        "crowdingLevel"  to crowdingLevel,
        "detections"     to detections.get()
    )
}
