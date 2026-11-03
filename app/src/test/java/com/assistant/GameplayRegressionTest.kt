package com.assistant

import com.assistant.gameplay.*
import com.assistant.runtime.*
import org.junit.Assert.*
import org.junit.Test

class GameplayRegressionTest {
    private fun player(id: Int, mine: Boolean, x: Float, y: Float = 50f) =
        TrackedPlayer(id, x, y, 0f, 0f, confidence = 0.9f, isUserTeam = mine, lastSeenFrame = 1)
    private fun frame() = RuntimeFrame(1, 1, false, 100f, 50f, 6f, 0f,
        2, 1, 0, 0, 0f, 0f, 0f, 0.5f, ZoneDistribution(), 0.9f, true, false,
        entities = listOf(RuntimeEntity(1, 110f, 50f, 0f, 0f, 0.9f, true),
            RuntimeEntity(2, 100f, 50f, 1f, 0f, 0.9f, false)),
        ownerId = 2, defenderId = 1, attackDirection = 1, captureWidth = 640f, captureHeight = 288f)
    @Test fun associationPreservesTeamIdentityAtContact() {
        val tracks = mutableListOf(player(1, true, 20f), player(2, false, 21f))
        EntityAssociationEngine.associate(tracks, emptyList(), 2)
        assertEquals(2, tracks.size)
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
    @Test fun bumperUsesObservedMotionAndCarriesTackleIntent() {
        val c = DreadnoughtBumperEngine().contribute(frame())!!
        assertEquals(103f, c.targetX); assertEquals(2, c.controlSlot)
        assertEquals(110f, c.aimOriginX!!)
    }
    @Test fun interceptRejectsPathBehindBall() {
        val f = frame().copy(entities = frame().entities.map { if (it.id == 1) it.copy(x = 90f) else it })
        assertNull(AbsoluteMagneticFeetEngine().contribute(f))
        assertNotNull(AbsoluteMagneticFeetEngine().contribute(frame()))
    }
    @Test fun nonFiniteMotionCannotBeTrusted() {
        assertFalse(frame().copy(ballVelocityX = Float.NaN).trusted)
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
        val first = SceneTracker.update(GameStateSnapshot(),
            PlayerDetectionResult(true, 1, 0.9f, listOf(PlayerDetection(333f, 50f, 0.9f, true))))
        val before = first.trackedPlayers.first { it.isUserTeam }.x
        SceneTracker.update(GameStateSnapshot(),
            PlayerDetectionResult(true, 1, 0.9f, listOf(PlayerDetection(338f, 50f, 0.9f, true))))
        assertEquals(before, first.trackedPlayers.first { it.isUserTeam }.x)
    }
    @Test fun laserRejectsObstructionAndLeadsMovingReceiver() {
        val passer = RuntimeEntity(1, 100f, 50f, 0f, 0f, 0.9f, true)
        val receiver = RuntimeEntity(3, 200f, 50f, 1f, 0f, 0.9f, true)
        val opponent = RuntimeEntity(2, 150f, 50f, 0f, 0f, 0.9f, false)
        val f = frame().copy(hasBall = true, ownerId = 1, entities = listOf(passer, receiver, opponent))
        assertNull(LaserPassDirectorEngine().contribute(f))
        val c = LaserPassDirectorEngine().contribute(f.copy(entities = listOf(passer, receiver, opponent.copy(y = 120f))))!!
        assertEquals(205f, c.targetX); assertEquals(1, c.controlSlot)
    }
    @Test fun finisherAimsAwayFromObservedKeeperAndRequiresVisibleGoal() {
        val f = frame().copy(hasBall = true, ownerId = 1, goalDetected = true,
            goalLeftX = 130f, goalRightX = 170f, goalTopY = 40f, goalBottomY = 60f,
            goalConfidence = 0.9f, goalkeeperVisible = true, goalkeeperX = 135f, goalkeeperY = 50f)
        assertEquals(166f, ApexFinisherEngine().contribute(f)!!.targetX)
        assertNull(ApexFinisherEngine().contribute(f.copy(goalDetected = false)))
    }
    @Test fun anchorUsesAttackAxisInEitherDirection() {
        val teammates = listOf(RuntimeEntity(3, 30f, 30f, 0f, 0f, 0.9f, true),
            RuntimeEntity(4, 40f, 90f, 0f, 0f, 0.9f, true), RuntimeEntity(5, 50f, 120f, 0f, 0f, 0.9f, true))
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
    }
}
