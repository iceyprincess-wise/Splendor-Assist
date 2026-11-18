package com.assistant

import com.assistant.vision.CameraProfile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ObservedTacticsTest {
    // No54-D1: two tests in this file set the GLOBAL CameraProfile scale and
    // never restored it, so the 640x300 scale leaked into every test class
    // that ran later in the same JVM (captureHeightOrFallback falls back to
    // 720 only while captureHeight is 0). Save/restore around every test.
    private var savedCaptureWidth = 0
    private var savedCaptureHeight = 0
    private var savedSourceWidth = 0
    private var savedSourceHeight = 0

    @Before fun setUp() {
        savedCaptureWidth = CameraProfile.captureWidth
        savedCaptureHeight = CameraProfile.captureHeight
        savedSourceWidth = CameraProfile.sourceWidth
        savedSourceHeight = CameraProfile.sourceHeight
        // No54-D5: the recognition engines are stateful singletons with no
        // reset before this round; the tests below self-seeded by luck of
        // call order. Reset explicitly so JUnit's hash method order (not
        // source order) can never flip a result.
        CounterPressRecognitionEngine.reset()
        PossessionStyleRecognitionEngine.reset()
    }

    @After fun tearDown() {
        CameraProfile.setCaptureScale(savedCaptureWidth, savedCaptureHeight, savedSourceWidth, savedSourceHeight)
    }

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
    // No68-SYNTH (g5 harness failure): No68-I-D24 changed analyze() to
    // judge pressure AT THE BALL (cellPressureAt) instead of the old mean,
    // and ballVisible=false (the default) means "not detected". The old
    // 3-arg calls therefore returned not-detected and the assertTrue
    // failed. Updated to the new contract: same 0.4 threshold, same
    // 0.8 compactness, same found formation — but the ball is now visible
    // at the centre of the 288x640 frame, so the ball cell carries the
    // grid value (uniform 0.8 -> detected; uniform 0.0 -> not detected).
    // Renamed: the metric pinned is ball-cell pressure, not mean pressure.
    @Test fun pressingTriggersOnBallCellPressureAboveThreshold() {
        // No54-D4: renamed — the old name claimed the test varied grid
        // dimensions, but it never does; what it pins is the mean-pressure
        // threshold (0.4) against a fixed compactness (0.8 > 0.55) and a
        // found formation.
        val shape=DefensiveCompactnessResult(compactness=0.8f,confidence=0.8f)
        val formation=FormationResult(true,confidence=0.8f)
        assertFalse(PressingRecognitionEngine.analyze(pressure(0f),shape,formation,ballX=144f,ballY=320f,frameWidth=288f,frameHeight=640f,ballVisible=true).detected)
        assertTrue(PressingRecognitionEngine.analyze(pressure(0.8f),shape,formation,ballX=144f,ballY=320f,frameWidth=288f,frameHeight=640f,ballVisible=true).detected)
    }
    @Test fun counterpressRequiresLossRatherThanTeammatePassOrRegain() {
        val scene=SceneSnapshot(ballVisible=true,confidence=0.8f)
        CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(false),pressure(0.8f),1L)
        assertFalse(CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(true,possessionChanged=true),pressure(0.8f),2L).detected)
        assertFalse(CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(true,possessionChanged=true),pressure(0.8f),3L).detected)
        assertTrue(CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(false),pressure(0.8f),4L).detected)
        // No54-D7: 900_000_004L is 900ms after the loss recorded at t=4
        // (900_000_000ns), not 900 seconds — outside the 800ms window, so
        // the press is no longer live.
        assertFalse(CounterPressRecognitionEngine.analyze(scene,BallPossessionResult(false),pressure(0.8f),900_000_004L).detected)
    }
    @Test fun ownPlayersDoNotManufactureOpponentPressureAndOutputIsDeterministic() {
        val scene=SceneSnapshot(frameNumber=1,trackedPlayers=listOf(player(1,true,30f)))
        assertTrue(PressureFieldEngine.compute(scene,640f,300f).pressure.all { row -> row.all { it==0f } })
        val withOpponent=scene.copy(trackedPlayers=listOf(player(1,false,30f)))
        val a=PressureFieldEngine.compute(withOpponent,640f,300f);val b=PressureFieldEngine.compute(withOpponent,640f,300f)
        assertTrue(a.pressure.indices.all { a.pressure[it].contentEquals(b.pressure[it]) })
    }
    @Test fun mismatchedSpatialGridShapesProduceUnknownInsteadOfCrashing() {
        // No54-D2: the old test folded a shape mismatch AND a NaN into one
        // fixture, so it could not say which guard fired. Shape only:
        // a 2x2 result carrying a single row, and a 2x2 occupancy
        // carrying a single row.
        val malformedShape=PressureFieldResult(2,2,arrayOf(floatArrayOf(0.5f)))
        assertNull(ObservedTactics.pressure(malformedShape))
        assertTrue(OpenSpaceDetectionEngine.analyze(SpaceOccupancyResult(2,2,arrayOf(intArrayOf(1))),pressure(0f),640f,300f).cells.isEmpty())
    }
    @Test fun nanSpatialGridValuesProduceUnknownInsteadOfCrashing() {
        // No54-D2: value only — shapes are correct, the values are NaN.
        val malformedValue=PressureFieldResult(1,1,arrayOf(floatArrayOf(Float.NaN)))
        assertNull(ObservedTactics.pressure(malformedValue))
        val nanField=PressureFieldResult(2,2,Array(2) { FloatArray(2) { Float.NaN } })
        assertTrue(OpenSpaceDetectionEngine.analyze(SpaceOccupancyResult(2,2,Array(2) { IntArray(2) }),nanField,640f,300f).cells.isEmpty())
        // Valid control: a well-formed grid still measures.
        assertEquals(0.5f,ObservedTactics.pressure(pressure(0.5f))!!,0f)
    }
    @Test fun asymmetricGridDimensionsAreReadAsColumnsThenRows() {
        // No54-D3: every pressure() fixture in this file is a symmetric
        // 2x2, so a columns/rows transposition was invisible. A 3-wide,
        // 2-tall grid pins the order: cell (col 2, row 1) sits at
        // centerX = 2.5*300/3 = 250, centerY = 1.5*200/2 = 150.
        val occupancy=SpaceOccupancyResult(3,2,arrayOf(intArrayOf(0,0,0),intArrayOf(0,0,0)))
        val field=PressureFieldResult(3,2,arrayOf(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f)))
        val result=OpenSpaceDetectionEngine.analyze(occupancy,field,300f,200f)
        assertEquals(6,result.cells.size)
        val cell=result.cells.first { it.column==2 && it.row==1 }
        assertEquals(250f,cell.centerX,0f)
        assertEquals(150f,cell.centerY,0f)
        assertTrue(cell.viable)
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
        // No54-D6: the old test asserted only `blocked`; the cell lookup
        // itself was implicit. The lane midpoint (525,225) maps to
        // col (525/640*2)=1, row (225/300*2)=1, and cell (1,1) holds 1f,
        // so pin the looked-up pressure value too.
        assertTrue(graph.lanes.all { it.pressure==1f })
    }
}
