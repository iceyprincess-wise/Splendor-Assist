object RuntimeConfidenceCalibrationEngine {
    private val outBuffer = FloatArray(1)
    fun analyze(tactical:TacticalIntelligenceResult, formation:FormationAdaptationResult, passing:PreferredPassingLaneLearningResult, shooting:ShootingHabitLearningResult, temporal:TemporalMemoryState):RuntimeConfidenceCalibrationResult {
        com.assistant.NativeBridge.nativeRuntimeConfidenceCalibration(tactical.confidence, formation.confidence, passing.confidence, shooting.confidence, temporal.exponentialMovingAverage, temporal.rollingMean, temporal.temporalConfidence, outBuffer)
        return RuntimeConfidenceCalibrationResult(calibratedConfidence = outBuffer[0])
    }
}
/* ======
RuntimeConfidenceCalibrationEngine Anchor
====== */

/* ========
RuntimeDecisionLoop
======== */
/*
 * The single per-frame decision path.
 *
 *   frame -> registry.collect -> pick highest-weight contribution
 *         -> also fold in ContributionRegistry.drainBest (emergency submitters)
 *         -> ONE ExecutionRequest routed through the terminal
 *
 * REPAIRED (Task C - FIELD-LOG PROVEN): arbitration was raw max-weight.
 * The 18:38 session shows what that produced: MagneticFeet (a MOVE-class
 * stabilizer that fires with authority~1.0 on EVERY possession frame) won
 * nearly every cycle - lastAction=MagneticFeet:MOVE, weight=1.0 - so 6131
 * accepted dispatches were overwhelmingly ball-position taps while Shot
 * won 1767, PassLane 907, CrossDelivery 23. Actions existed; they were
 * being outvoted by a stabilizer.
 *
 * Fix: ACTION-CLASS ARBITRATION SCALING. Real match actions (SHOT, PASS,
 * CROSS, DEFEND, KEEPER, EVADE) keep full weight; MOVE support is scaled
 * down so it wins only when nothing real is on offer. Admin-tunable live:
 *   MOVE support uses the live class scale of 0.35 in this implementation.
 * Raise it if movement support feels too weak, lower it if MOVE spam
 * returns - no rebuild needed.
 */
