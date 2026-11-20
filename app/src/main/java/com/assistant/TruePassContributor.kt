package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*





import kotlin.math.hypot






















object TruePassContributor : GameplayContributor {
    override val engineName = "TruePass"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall || frame.viableLaneCount <= 0) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null } ?: return null
        // No68-A-D12: no67-D12 added the per-player lastSeenFrame filter
        // but left the scene itself unchecked — SceneTracker retains
        // tracks for 15 frames, so a stalled vision pipeline could feed
        // this contributor a stale scene whose players still pass the
        // per-player filter (they were seen in that old frame). Mirror
        // FrameAssembler's 0–250 ms freshness gate exactly.
        if (System.currentTimeMillis() - scene.timestamp !in 0L..250L) return null
        val players = scene.trackedPlayers
        val size = players.size

        // No68-A-D12: prefer the ACTUAL best-lane receiver.
        // FrameAssembler sets frame.passTargetId to the receiver of the
        // best viable lane — the player the lane graph chose. The old
        // code ignored it and took the nearest teammate to the (crossing)
        // pass target instead, which can be a different player standing
        // within 120 px of that target. Fall back to nearest only when
        // the id player is absent, stale, or the keeper.
        var bestReceiver: TrackedPlayer? = null
        var minDistance = Float.MAX_VALUE
        val targetId = frame.passTargetId
        if (targetId != null) {
            for (i in 0 until size) {
                val p = players[i]
                if (p.id == targetId && p.isUserTeam && !p.isGoalkeeper &&
                    p.lastSeenFrame == scene.frameNumber
                ) {
                    bestReceiver = p
                    minDistance = hypot(
                        (p.x - frame.passTargetX).toDouble(),
                        (p.y - frame.passTargetY).toDouble()
                    ).toFloat()
                    break
                }
            }
        }

        // The id receiver is normally ~40 px from the pass target (the
        // crossing target is the receiver shifted −40 in Y), so it is
        // inside the 120 px advanced-branch radius. If it ever is not
        // (the receiver sprinted between assembly and contribution),
        // do NOT lock in a receiver the advanced branch will reject —
        // drop it and let the nearest scan below pick the near teammate
        // the old code would have picked.
        if (bestReceiver != null && minDistance >= 120f) {
            bestReceiver = null
            minDistance = Float.MAX_VALUE
        }

        // Zero-allocation indexed loop
        if (bestReceiver == null) {
            for (i in 0 until size) {
                val p = players[i]
                if (p.isUserTeam && !p.isGoalkeeper && p.lastSeenFrame == scene.frameNumber) {
                    val d = hypot((p.x - frame.passTargetX).toDouble(), (p.y - frame.passTargetY).toDouble()).toFloat()
                    if (d < minDistance) {
                        minDistance = d
                        bestReceiver = p
                    }
                }
            }
        }

        // No68-A-D13: the nearest fresh opponent to the receiver, for
        // the defender-aware escape offset. Without a real defender
        // position the old offset dodged to a fixed side and could move
        // the target TOWARD the defender; null means "no defender
        // known" and the engine skips the offset.
        var nearestDefender: TrackedPlayer? = null
        if (bestReceiver != null) {
            var minDefDistance = Float.MAX_VALUE
            for (i in 0 until size) {
                val p = players[i]
                if (!p.isUserTeam && p.lastSeenFrame == scene.frameNumber) {
                    val d = hypot((p.x - bestReceiver.x).toDouble(), (p.y - bestReceiver.y).toDouble()).toFloat()
                    if (d < minDefDistance) {
                        minDefDistance = d
                        nearestDefender = p
                    }
                }
            }
        }

        val r = if (bestReceiver != null && minDistance < 120f) {
            TrueTargetPassingEngine.optimizeWithAdvancedTactics(
                frame.ballX, frame.ballY,
                bestReceiver.x, bestReceiver.y,
                bestReceiver.velocityXPerSecond, bestReceiver.velocityYPerSecond,
                frame.defenderDensity,
                nearestDefender?.x, nearestDefender?.y
            )
        } else {
            // No67-D11: retention 1.0f — the fallback target is the real
            // pass target, not a fraction of the way there (at low
            // confidence the old fraction was a noise target ~30 px from
            // the ball). Authority is still confidence-shaped.
            TrueTargetPassingEngine.optimize(
                frame.ballX, frame.ballY,
                frame.passTargetX, frame.passTargetY,
                1.0f
            )
        }

        val authority = ((1f - r.interceptionRisk) * frame.bestLaneConfidence.coerceAtLeast(0.3f)).coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = r.correctedX,
            targetY = r.correctedY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}

