package com.assistant

import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.*

object ShotOpportunityContributor : GameplayContributor {
    override val engineName = "ShotOpportunity"
    override val capabilities = setOf(EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (!frame.goalDetected) return null
        // No68-F-D8: the D31 else-branch is dead — !goalDetected returns
        // null above, so the fallback distance could never run, and the
        // `if (frame.goalDetected)` re-test was always true. Direct hypot.
        val goalDistance = hypot(frame.goalCenterX - frame.ballX, frame.goalCenterY - frame.ballY)
        val r = ShotOpportunityAnalysisEngine.analyze(goalDistance, frame.defenderDensity)
        if (r.openSideScore <= 0f) return null
        // No67-D33: the old target was the ball itself, so chooseRequest saw
        // a zero drag vector and dropped every dispatch. Aim at the detected
        // goal; without a detected goal there is no shot to take.
        // No68-F-D7 (closes Q67-26): aim at the OPEN side, not the centre
        // — the keeper stands at the centre, and this engine's own score
        // is an open-side score. Same rule as ShotContributor: inset 0.18
        // of the goal width, away from the keeper when visible, centre
        // when not. A degenerate box (width <= 1) has no computable
        // inset aim, so there is no shot.
        if (frame.goalWidth <= 1f) return null
        val inset = frame.goalWidth * 0.18f
        val leftAim = frame.goalLeftX + inset
        val rightAim = frame.goalRightX - inset
        val aimX = when {
            !frame.goalkeeperVisible -> frame.goalCenterX
            frame.goalkeeperX <= frame.goalCenterX -> rightAim
            else -> leftAim
        }
        // No68-F-D34 (completion round): the aim was clamped only
        // >= 0 — the same one-sided clamp D5 fixed in
        // ShotAnticipationContributor. The goal box can extend past the
        // capture edge (the goal sits at an X end), so rightAim /
        // lowAimY could exceed captureWidth/captureHeight and the
        // dispatch would aim off-screen. Clamp both axes to the capture,
        // same form as D5; unchanged for any on-screen goal.
        // B2-2 (item 23): align the duration hint to ShotTypeEngine — the
        // same rule ShotContributor already uses. The 35L literal matched
        // POWER only: mid/long FINESSE wants 30L (this fired 5ms long)
        // and keeper-stranded CHIP wants 25L (this fired 10ms long). Aim
        // stays the open-side inset aim above; duration only.
        // Shot core fix (item 3): default aim is LOW, not mid-height
        // (goalCenterY) — mid-height aims at the keeper's chest and,
        // on far-corner paths, at the bar line. Low = today's POWER
        // placement. ShotTypeEngine still owns the CHIP high aim; this
        // engine uses its duration only, as before.
        val goalHeight = (frame.goalBottomY - frame.goalTopY).coerceAtLeast(1f)
        val lowAimY = (frame.goalBottomY - goalHeight * 0.15f).coerceIn(frame.goalTopY, frame.goalBottomY)
        val durationHintMs = ShotTypeEngine.select(frame, aimX, lowAimY)?.durationHintMs ?: 35L
        return EngineContribution(engineName, ActionClass.SHOT,
            aimX.coerceIn(0f, frame.captureWidth), lowAimY.coerceIn(0f, frame.captureHeight),
            (r.openSideScore / 4f).coerceIn(0f, 1f), r.confidence.coerceIn(0f, 1f), durationHintMs)
    }
}
