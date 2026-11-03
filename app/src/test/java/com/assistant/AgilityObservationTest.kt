package com.assistant

import com.assistant.runtime.*
import org.junit.Assert.*
import org.junit.Test

class AgilityObservationTest {
    private fun frame(): RuntimeFrame = RuntimeFrame(1,1,true,20f,20f,
        playerCount=2,opponentCount=1,laneCount=0,viableLaneCount=0,passTargetX=0f,passTargetY=0f,
        bestLaneConfidence=.1f,defenderDensity=.1f,zones=ZoneDistribution(),confidence=.9f,enabled=true,panic=false,
        ownerId=1,captureWidth=640f,captureHeight=288f,
        entities=listOf(RuntimeEntity(1,20f,20f,0f,0f,.9f,true,30f,40f),
            RuntimeEntity(2,23f,24f,0f,0f,.9f,false)))
    @Test fun speedAndDistanceComeFromTrackedMotionAndCoordinates() {
        val result=observeAgility(frame())!!
        assertEquals(50f,result.speed,0.001f);assertEquals(5f,result.distance,0.001f)
    }
    @Test fun confidenceAndDensityDoNotPoseAsMotionOrDistance() {
        val first=observeAgility(frame())!!
        val next=observeAgility(frame().copy(bestLaneConfidence=.99f,defenderDensity=.99f,confidence=.5f))!!
        assertEquals(first.speed,next.speed,0f);assertEquals(first.distance,next.distance,0f)
        assertEquals(.5f,next.confidence,0f)
    }
    @Test fun missingCarrierOpponentOrMotionProducesNoInventedInputs() {
        val f=frame();assertNull(observeAgility(f.copy(ownerId=null)))
        assertNull(observeAgility(f.copy(entities=f.entities.filter{it.teammate})))
        assertNull(observeAgility(f.copy(entities=listOf(f.entities[0].copy(velocityXPerSecond=Float.NaN),f.entities[1]))))
    }
    @Test fun turnComesFromHeadingAndRequestedDirection() {
        val f=frame();val straight=observeAgility(f)!!;assertEquals(0f,straight.turnIntensity,.001f)
        val turning=observeAgility(f.copy(passTargetId=3,passTargetX=-10f,passTargetY=-20f))!!
        assertTrue(turning.turnIntensity>.9f)
    }
}
