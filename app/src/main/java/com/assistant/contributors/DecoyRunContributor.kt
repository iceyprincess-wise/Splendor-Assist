package com.assistant.contributors

import com.assistant.DecoyPhase
import com.assistant.DecoyRunEngine
import com.assistant.SceneTracker
import com.assistant.TrueTargetPassingEngine
import com.assistant.runtime.*

/*
 * Decoy-run contributor (queue item 3, Ed 2026-11-20 ~14:58 WAT).
 *
 * The engine (DecoyRunEngine) holds the phase machine — IDLE → DRAG →
 * AIM → IDLE — and this contributor is only the adapter that turns one
 * frame's decision into the single offer the arbiter takes:
 *   DRAG → MOVE toward the drag point (the carrier's false run, pulling
 *          the presser off the safe lane).
 *   AIM  → PASS aimed at the safe-lane receiver's live position, so the
 *          user's pass goes there once the lane opens.
 * The pass itself is the user's press — the app only aims (D13:
 * through passes stay USER-ONLY; this contributor never offers THROUGH,
 * and the engine never aims a through ball).
 *
 * Singleton reads follow TruePassContributor exactly: SceneTracker with
 * the 0..250 ms freshness gate, the passing graph of the same vision
 * cycle. The engine itself takes all three inputs explicitly and is the
 * harness-tested surface; this adapter is source-pinned (g35s).
 *
 * Arbitration note (ConAIEngine): a MOVE offer wins only when no
 * tactical candidate has authority >= 0.30, so the drag fires exactly
 * when no good immediate pass exists. The AIM offer is an ordinary
 * tactical PASS candidate. No arbitration change was made.
 */
object DecoyRunContributor : GameplayContributor {
    override val engineName = "DecoyRun"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK, EngineCapability.MOVEMENT)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val scene = try { SceneTracker.current() } catch (_: Throwable) { null } ?: return null
        // TruePassContributor's freshness gate, mirrored exactly: a
        // stalled vision pipeline must not feed a stale scene.
        if (System.currentTimeMillis() - scene.timestamp !in 0L..250L) return null
        val graph = try { TrueTargetPassingEngine.currentPassingGraph() } catch (_: Throwable) { null }
            ?: return null
        val decision = DecoyRunEngine.decide(frame, scene, graph)
        if (!decision.viable) return null
        return when (decision.phase) {
            DecoyPhase.DRAG -> EngineContribution(
                engine = engineName,
                actionClass = ActionClass.MOVE,
                targetX = decision.dragTargetX.coerceIn(0f, frame.captureWidth),
                targetY = decision.dragTargetY.coerceIn(0f, frame.captureHeight),
                authority = decision.authority,
                confidence = frame.confidence,
                durationHintMs = 40L,
                targetEntityId = decision.receiverId
            )
            DecoyPhase.AIM -> EngineContribution(
                engine = engineName,
                actionClass = ActionClass.PASS,
                targetX = decision.targetX.coerceIn(0f, frame.captureWidth),
                targetY = decision.targetY.coerceIn(0f, frame.captureHeight),
                authority = decision.authority,
                confidence = frame.confidence,
                durationHintMs = 45L,
                targetEntityId = decision.receiverId
            )
            else -> null
        }
    }

    override fun reset() {
        DecoyRunEngine.reset()
    }
}
