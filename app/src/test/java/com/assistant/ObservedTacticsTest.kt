package com.assistant

import com.assistant.vision.CameraProfile
import org.junit.Assert.*
import org.junit.Test

class ObservedTacticsTest {
    private fun player(id: Int, mine: Boolean, y: Float) =
        TrackedPlayer(id, 150f, y, 0f, 0f, confidence=0.8f, isUserTeam=mine, lastSeenFrame=1)
    private fun pressure(value: Float) = PressureFieldResult(2,2,Array(2) { FloatArray(2) { value } })
    @Test fun regionalOverloadDependsOnTeamLocationsNotTotalHeadcount() {
        CameraProfile.setCaptureScale(640,300,1600,750)
        val ours = (1..3).map { player(it,true,30f) }; val theirs=(4..6).map { player(it,false,270f) }
        val scene=SceneSnapshot(frameNumber=1,playerCount=6,trackedPlayers=ours+theirs,confidence=0.8f)
        val wing=WingOverloadDetectionEngine.compute(scene,SpaceOccupancyResult(2,2,Array(2) { IntArray(2) }),pressure(0.2f))
        assertEquals(1f,wing.leftWingAdvantage);assertEquals(-1f,wing.rightWingAdvantage);assertTrue(wing.overloaded)
        assertFalse(CentralOverloadDetectionEngine.compute(scene).overloaded)
        val center=scene.copy(trackedPlayers=ours.map { it.copy(y=150f) })
        assertTrue(CentralOverloadDetectionEngine.compute(center).overloaded)
    }
    @Test fun pressingReadsCellValuesWithIdenticalGridDimensions() {
        val shape=DefensiveCompactnessResult(compactness=0.8f,confidence=0.8f)
        val formation=FormationResult(true,confidence=0.8f)
        assertFalse(PressingRecognitionEngine.analyze(pressure(0f),shape,formation).detected)
        assertTrue(PressingRecognitionEngine.analyze(pressure(0.8f),shape,formation).detected)
    }
    @Test fun counterpressRequiresLossRatherThanTeammatePassOrRegain() {
        val scene=SceneSnapshot(ballVisible=true,confidence=0.8f)
        CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(false),pressure(0.8f),1L)
        assertFalse(CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(true,possessionChanged=true),pressure(0.8f),2L).detected)
        assertFalse(CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(true,possessionChanged=true),pressure(0.8f),3L).detected)
        assertTrue(CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(false),pressure(0.8f),4L).detected)
        assertFalse(CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(false),pressure(0.8f),900_000_004L).detected)
    }
    @Test fun ownPlayersDoNotManufactureOpponentPressureAndOutputIsDeterministic() {
        val scene=SceneSnapshot(frameNumber=1,trackedPlayers=listOf(player(1,true,30f)))
        assertTrue(PressureFieldEngine.compute(scene,640f,300f).pressure.all { row -> row.all { it==0f } })
        val withOpponent=scene.copy(trackedPlayers=listOf(player(1,false,30f)))
        val a=PressureFieldEngine.compute(withOpponent,640f,300f);val b=PressureFieldEngine.compute(withOpponent,640f,300f)
        assertTrue(a.pressure.indices.all { a.pressure[it].contentEquals(b.pressure[it]) })
    }
    @Test fun mismatchedOrNanSpatialGridsProduceUnknownInsteadOfCrashing() {
        val malformed=PressureFieldResult(2,2,arrayOf(floatArrayOf(Float.NaN)))
        assertNull(ObservedTactics.pressure(malformed))
        assertTrue(OpenSpaceDetectionEngine.analyze(SpaceOccupancyResult(2,2,arrayOf(intArrayOf(1))),pressure(0f),640f,300f).cells.isEmpty())
    }
    @Test fun possessionStyleUsesElapsedTimeNotNumberOfEvaluations() {
        val passer=player(1,true,100f);val receiver=player(2,true,150f)
        val graph=PassingLaneGraph(listOf(PassingLane(passer,receiver,50f,0.1f,false,0.8f)))
        PossessionStyleRecognitionEngine.analyze(BallPossessionResult(false),graph,pressure(0.2f),0L)
        val possession=BallPossessionResult(true,confidence=0.8f)
        for(i in 0..100) assertFalse(PossessionStyleRecognitionEngine.analyze(possession,graph,pressure(0.2f),i*10_000_000L).detected)
        assertTrue(PossessionStyleRecognitionEngine.analyze(possession,graph,pressure(0.2f),1_500_000_000L).detected)
    }
    @Test fun lanePressureUsesActualCaptureScale() {
        CameraProfile.setCaptureScale(640,300,1600,750)
        val a=player(1,true,200f).copy(x=500f);val b=player(2,true,250f).copy(x=550f)
        val field=PressureFieldResult(2,2,arrayOf(floatArrayOf(0f,0f),floatArrayOf(0f,1f)))
        val graph=PassingLaneGraphEngine.build(SceneSnapshot(frameNumber=1,trackedPlayers=listOf(a,b)),field)
        assertEquals(2,graph.lanes.size);assertTrue(graph.lanes.all { it.blocked })
    }
}
