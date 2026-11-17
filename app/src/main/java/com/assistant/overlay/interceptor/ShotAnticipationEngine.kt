package com.assistant.overlay.interceptor






enum class AnticipationResult {
    TRACK,
    SAVE,
    INTERCEPT,
    PANIC
}

object ShotAnticipationEngine {

    // No64-D7: the bonus threshold, named. predictionBonus is the
    // prediction slider (0-100, default 100) + interceptionBonus (0-30);
    // at defaults it is always >= threshold, so CROSS/LONG_BALL always
    // INTERCEPT via the bonus arms below unless the user drags the
    // prediction slider below ~70.
    private const val INTERCEPT_BONUS_THRESHOLD = 70

    // No64-D8: Anticipation from the live prediction bonus (slider + interception bonus) and the native priority ladder.
    fun evaluate(decision: ThreatDecision): AnticipationResult {
        val predictionBonus = InterceptionRuntimeRegistry.prediction + GoalkeeperAdaptiveFeedbackEngine.interceptionBonus()
        return when {
            // No64-D6: PANIC/SAVE are checked BEFORE the CROSS/LONG_BALL
            // intercept arms. The old order put the bonus arms first, so
            // at default sliders (bonus always 100-130) every CROSS /
            // LONG_BALL decision was INTERCEPT and SAVE/PANIC were
            // unreachable for those directions. NEAR_POST/FAR_POST/CENTER
            // behaviour is unchanged (the bonus arms never applied to them).
            // SPLENDOR_P10_SAT_PANIC: PANIC 130 -> 150; SAVE [100,150).
            decision.priority >= PANIC_PRIORITY_THRESHOLD -> AnticipationResult.PANIC
            decision.priority >= 100 -> AnticipationResult.SAVE
            decision.direction == ShotDirection.CROSS && predictionBonus >= INTERCEPT_BONUS_THRESHOLD -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.LONG_BALL && predictionBonus >= INTERCEPT_BONUS_THRESHOLD -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.CROSS && decision.priority >= 90 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.LONG_BALL && decision.priority >= 85 -> AnticipationResult.INTERCEPT
            decision.priority >= 75 -> AnticipationResult.INTERCEPT
            else -> AnticipationResult.TRACK
        }
    }
}
