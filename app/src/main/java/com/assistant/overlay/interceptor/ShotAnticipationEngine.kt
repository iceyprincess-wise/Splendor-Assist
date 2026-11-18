package com.assistant.overlay.interceptor






enum class AnticipationResult {
    TRACK,
    SAVE,
    INTERCEPT,
    PANIC
}

object ShotAnticipationEngine {

    // No64-D7: the bonus threshold, named. predictionBonus is the
    // prediction slider (0-100, default 100) alone since No68-F-D15
    // (Q66-A3): the interceptionBonus used to be added here as well as
    // into priority via ThreatPriorityEngine, applying it twice to the
    // same ThreatDecision. At defaults the slider alone is always >=
    // threshold, so CROSS/LONG_BALL always INTERCEPT via the bonus arms
    // below unless the user drags the prediction slider below ~70.
    private const val INTERCEPT_BONUS_THRESHOLD = 70

    // No64-D8: Anticipation from the live prediction bonus (slider) and the native priority ladder.
    fun evaluate(decision: ThreatDecision): AnticipationResult {
        // No68-F-D15 (closes Q66-A3, this side): the prediction bonus is
        // the user's slider alone. GoalkeeperAdaptiveFeedbackEngine
        // .interceptionBonus() (0/10/20/30 by career interceptions) ALSO
        // feeds priority in ThreatPriorityEngine, so adding it here
        // applied one quantity twice to the same decision — and at
        // default sliders it changed nothing here, while below 70 it
        // silently overrode the user's lowered slider. Removing it here
        // is behaviour-neutral at defaults; the priority-site application
        // stays (ThreatPriorityEngine.kt, another family's file).
        val predictionBonus = InterceptionRuntimeRegistry.prediction
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
            // No68-F-D16: the old catch-all labelled EVERY direction
            // INTERCEPT at priority >= 75, including NEAR_POST / FAR_POST /
            // CENTER — but the router's INTERCEPT arm can only act on a
            // cross, a long ball, or a shade bias, so those threats fell
            // through to TRACK (keeper stands still) under a wrong label.
            // Scope the catch-all to the directions the router can act on;
            // post/centre threats at 75-99 now read TRACK honestly (same
            // routed action — the keeper-still question itself is QF-8).
            (decision.direction == ShotDirection.CROSS || decision.direction == ShotDirection.LONG_BALL) && decision.priority >= 75 -> AnticipationResult.INTERCEPT
            else -> AnticipationResult.TRACK
        }
    }
}
