package com.assistant

import com.assistant.gameplay.*
import com.assistant.runtime.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GameplayRegressionTest {
    @Before fun setUp() {
        // No55-D4: BallPossessionEngine, SceneTracker and
        // EntityAssociationEngine are stateful singletons with no reset
        // before this round; the possession tests self-seeded with a
        // compute(false) call and the snapshot test depended on an empty
        // SceneTracker. Reset explicitly so JUnit's hash method order
        // (not source order) can never flip a result.
        BallPossessionEngine.reset()
        SceneTracker.reset()
        EntityAssociationEngine.reset()
    }

    private fun player(id: Int, mine: Boolean, x: Float, y: Float = 50f) =
        TrackedPlayer(id, x, y, 0f, 0f, confidence = 0.9f, isUserTeam = mine, lastSeenFrame = 1)
    // No55-D3: the old frame() passed all 19 RuntimeFrame args
    // positionally — a future field insertion before the tail would
    // silently transpose the fixture. Every arg is now named (the same
    // pattern AgilityObservationTest already uses), so the mapping is
    // checked by the compiler, not by counting.
    private fun frame() = RuntimeFrame(
        frameId = 1, timestampMs = 1, hasBall = false, ballX = 100f, ballY = 50f,
        ballVelocityX = 6f, ballVelocityY = 0f, playerCount = 2, opponentCount = 1,
        laneCount = 0, viableLaneCount = 0, passTargetX = 0f, passTargetY = 0f,
        bestLaneConfidence = 0f, defenderDensity = 0.5f, zones = ZoneDistribution(),
        confidence = 0.9f, enabled = true, panic = false,
        entities = listOf(
            RuntimeEntity(id = 1, x = 110f, y = 50f, velocityX = 0f, velocityY = 0f, confidence = 0.9f, teammate = true),
            RuntimeEntity(id = 2, x = 100f, y = 50f, velocityX = 1f, velocityY = 0f, confidence = 0.9f, teammate = false)),
        ownerId = 2, defenderId = 1, attackDirection = 1, captureWidth = 640f, captureHeight = 288f)
    @Test fun associationPreservesTeamIdentityAtContact() {
        val tracks = mutableListOf(player(1, true, 20f), player(2, false, 21f))
        EntityAssociationEngine.associate(tracks, emptyList(), 2)
        // No55-D2: the old test asserted only the count, never the team
        // identity the name promises. The two tracks are 1px apart —
        // inside the 12px duplicate-merge distance — so this pins that
        // the merge never fires across teams, and that neither track
        // moved nor changed team while coasting (both velocities are 0).
        assertEquals(2, tracks.size)
        assertEquals(1, tracks.count { it.isUserTeam })
        assertEquals(1, tracks.count { !it.isUserTeam })
        assertEquals(1, tracks.first { it.isUserTeam }.id)
        assertEquals(2, tracks.first { !it.isUserTeam }.id)
        assertEquals(20f, tracks.first { it.isUserTeam }.x, 0f)
        assertEquals(21f, tracks.first { !it.isUserTeam }.x, 0f)
    }
    @Test fun possessionSurvivesIndexReordering() {
        val p = player(10, true, 20f)
        BallPossessionEngine.compute(BallOwnershipResult(false))
        BallPossessionEngine.compute(BallOwnershipResult(true, p, 0, 0f, 0.9f))
        val second = BallPossessionEngine.compute(BallOwnershipResult(true, p, 1, 0f, 0.9f))
        assertFalse(second.possessionChanged); assertEquals(2L, second.possessionFrames)
    }
    @Test fun possessionDetectsDifferentOwnerAtSameIndex() {
        BallPossessionEngine.compute(BallOwnershipResult(false))
        BallPossessionEngine.compute(BallOwnershipResult(true, player(10, true, 20f), 0, 0f, 0.9f))
        assertTrue(BallPossessionEngine.compute(BallOwnershipResult(true, player(11, true, 20f), 0, 0f, 0.9f)).possessionChanged)
    }
    @Test fun allNewEnginesRejectPanicAndUntrustedFrames() {
        val engines = listOf(DreadnoughtBumperEngine(), FormationsAnchorEngine(),
            AbsoluteMagneticFeetEngine(), LaserPassDirectorEngine(), ApexFinisherEngine())
        engines.forEach { assertNull(it.contribute(frame().copy(panic = true)))
            assertNull(it.contribute(frame().copy(enabled = false))) }
    }
    @Test fun bumperUsesObservedPositionsAndCarriesTackleIntent() {
        // No55-D6: renamed — the bumper reads POSITIONS only (opponent vs
        // defender distance); no velocity is consulted anywhere in
        // DreadnoughtBumperEngine.contribute.
        val c = DreadnoughtBumperEngine().contribute(frame())!!
        assertEquals(100f, c.targetX); assertEquals(2, c.controlSlot)
        assertEquals(110f, c.aimOriginX!!)
        // Far branch: the defender 30px from the owner is past the close
        // threshold (15 + margin 0.5) but inside the outer gate (45 + 0.5),
        // so the same target is contributed with slot 4, not slot 2.
        val far = frame().copy(entities = listOf(
            RuntimeEntity(id = 1, x = 130f, y = 50f, velocityX = 0f, velocityY = 0f, confidence = 0.9f, teammate = true),
            RuntimeEntity(id = 2, x = 100f, y = 50f, velocityX = 1f, velocityY = 0f, confidence = 0.9f, teammate = false)))
        val farContribution = DreadnoughtBumperEngine().contribute(far)!!
        assertEquals(100f, farContribution.targetX)
        assertEquals(4, farContribution.controlSlot)
    }
    @Test fun interceptRejectsPathBehindBall() {
        val f = frame().copy(entities = frame().entities.map { if (it.id == 1) it.copy(x = 90f) else it })
        assertNull(AbsoluteMagneticFeetEngine().contribute(f))
        assertNotNull(AbsoluteMagneticFeetEngine().contribute(frame()))
    }
    @Test fun nonFiniteMotionCannotBeTrusted() {
        assertFalse(frame().copy(ballVelocityX = Float.NaN).trusted)
        // No55-D5: the old test pinned only ballVelocityX. The trusted
        // gate also rejects NaN ballVelocityY, a zero capture width, and
        // a confidence above 1 — pin all three, so a future narrowing of
        // the gate fails loudly instead of silently trusting bad frames.
        assertFalse(frame().copy(ballVelocityY = Float.NaN).trusted)
        assertFalse(frame().copy(captureWidth = 0f).trusted)
        assertFalse(frame().copy(confidence = 1.5f).trusted)
    }
    @Test fun associationNeverStealsAnOpponentDetection() {
        val tracks = mutableListOf(player(1, true, 20f))
        EntityAssociationEngine.associate(tracks, listOf(PlayerDetection(21f, 50f, 0.9f, false)), 2)
        assertEquals(2, tracks.size)
        assertEquals(20f, tracks.first { it.isUserTeam }.x)
    }
    @Test fun invalidObservationsCannotSpawnTracks() {
        val tracks = mutableListOf<TrackedPlayer>()
        EntityAssociationEngine.associate(tracks, listOf(PlayerDetection(Float.NaN, 5f, 0.9f, true)), 2)
        assertTrue(tracks.isEmpty())
    }
    @Test fun laterTrackingCannotMutatePublishedSnapshot() {
        // No55-D1: the old test compared `first` with itself and discarded
        // the second update's return, so it could never fail. Capture both
        // snapshots: the first must still read 333 after the second update
        // moved the live track to 338.
        val first = SceneTracker.update(GameStateSnapshot(),
            PlayerDetectionResult(true, 1, 0.9f, listOf(PlayerDetection(333f, 50f, 0.9f, true))))
        val second = SceneTracker.update(GameStateSnapshot(),
            PlayerDetectionResult(true, 1, 0.9f, listOf(PlayerDetection(338f, 50f, 0.9f, true))))
        assertEquals(333f, first.trackedPlayers.first { it.isUserTeam }.x, 0f)
        assertEquals(338f, second.trackedPlayers.first { it.isUserTeam }.x, 0f)
    }
    @Test fun laserRejectsObstructionAndUsesProvidedReceiver() {
        val passer = RuntimeEntity(id = 1, x = 100f, y = 50f, velocityX = 0f, velocityY = 0f, confidence = 0.9f, teammate = true)
        val receiver = RuntimeEntity(id = 3, x = 200f, y = 50f, velocityX = 1f, velocityY = 0f, confidence = 0.9f, teammate = true)
        val opponent = RuntimeEntity(id = 2, x = 150f, y = 50f, velocityX = 0f, velocityY = 0f, confidence = 0.9f, teammate = false)
        val f = frame().copy(hasBall = true, ownerId = 1, entities = listOf(passer, receiver, opponent))
        assertNull(LaserPassDirectorEngine().contribute(f))
        val c = LaserPassDirectorEngine().contribute(f.copy(entities = listOf(passer, receiver, opponent.copy(y = 120f))))!!
        assertEquals(200f, c.targetX); assertEquals(1, c.controlSlot)
    }
    @Test fun finisherAimsAwayFromObservedKeeperAndRequiresVisibleGoal() {
        val f = frame().copy(hasBall = true, ownerId = 1, goalDetected = true,
            goalLeftX = 130f, goalRightX = 170f, goalTopY = 40f, goalBottomY = 60f,
            goalConfidence = 0.9f, goalkeeperVisible = true, goalkeeperX = 135f, goalkeeperY = 50f)
        assertEquals(166f, ApexFinisherEngine().contribute(f)!!.targetX)
        assertNull(ApexFinisherEngine().contribute(f.copy(goalDetected = false)))
        // No55-D7: mirror the keeper to the far post — the finisher must
        // now aim at the near post (134), proving the aim is away from
        // the OBSERVED keeper, not a fixed side. And a goal confidence
        // below the 0.45 floor must reject even with a detected goal.
        assertEquals(134f, ApexFinisherEngine().contribute(f.copy(goalkeeperX = 165f))!!.targetX)
        assertNull(ApexFinisherEngine().contribute(f.copy(goalConfidence = 0.4f)))
    }
    @Test fun anchorUsesAttackAxisInEitherDirection() {
        val teammates = listOf(RuntimeEntity(id = 3, x = 30f, y = 30f, velocityX = 0f, velocityY = 0f, confidence = 0.9f, teammate = true),
            RuntimeEntity(id = 4, x = 40f, y = 90f, velocityX = 0f, velocityY = 0f, confidence = 0.9f, teammate = true), RuntimeEntity(id = 5, x = 50f, y = 120f, velocityX = 0f, velocityY = 0f, confidence = 0.9f, teammate = true))
        val f = frame().copy(entities = frame().entities + teammates)
        assertEquals(40f, FormationsAnchorEngine().contribute(f)!!.targetX)
        val mirrored = f.copy(attackDirection = -1, entities = f.entities.map { it.copy(x = 640f - it.x) })
        assertEquals(600f, FormationsAnchorEngine().contribute(mirrored)!!.targetX)
    }
    @Test fun panicRemainsTrustedForExistingEmergencyContributors() {
        assertTrue(frame().copy(panic = true).trusted)
    }
    @Test fun legacyAttackClassesResolveActionButtonsAndPreserveExplicitThroughPass() {
        val c = EngineContribution("legacy", ActionClass.PASS, 200f, 50f, 0.9f, 0.9f, 60L)
        assertEquals(3, RuntimeDecisionLoop.requiredControlSlot(c))
        assertEquals(2, RuntimeDecisionLoop.requiredControlSlot(c.copy(actionClass = ActionClass.SHOT)))
        assertEquals(3, RuntimeDecisionLoop.requiredControlSlot(c.copy(actionClass = ActionClass.CROSS)))
        assertEquals(1, RuntimeDecisionLoop.requiredControlSlot(c.copy(controlSlot = 1)))
        assertEquals(0, RuntimeDecisionLoop.requiredControlSlot(c.copy(actionClass = ActionClass.MOVE)))
        // No55-D8: DEFEND and NONE were unpinned; both resolve to slot 0
        // (legacy routing), not to any action button.
        assertEquals(0, RuntimeDecisionLoop.requiredControlSlot(c.copy(actionClass = ActionClass.DEFEND)))
        assertEquals(0, RuntimeDecisionLoop.requiredControlSlot(c.copy(actionClass = ActionClass.NONE)))
    }
}
