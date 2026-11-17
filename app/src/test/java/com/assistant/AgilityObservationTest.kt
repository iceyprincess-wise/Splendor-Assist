package com.assistant

import com.assistant.runtime.*
import org.junit.Assert.*
import org.junit.Test

class AgilityObservationTest {
    private fun frame(): RuntimeFrame = RuntimeFrame(
        frameId = 1, timestampMs = 1, hasBall = true, ballX = 20f, ballY = 20f,
        playerCount = 2, opponentCount = 1, laneCount = 0, viableLaneCount = 0, passTargetX = 0f, passTargetY = 0f,
        bestLaneConfidence = .1f, defenderDensity = .1f, zones = ZoneDistribution(), confidence = .9f, enabled = true, panic = false,
        ownerId = 1, captureWidth = 640f, captureHeight = 288f,
        entities = listOf(
            RuntimeEntity(id = 1, x = 20f, y = 20f, velocityX = 0f, velocityY = 0f, confidence = .9f, teammate = true, velocityXPerSecond = 30f, velocityYPerSecond = 40f),
            RuntimeEntity(id = 2, x = 23f, y = 24f, velocityX = 0f, velocityY = 0f, confidence = .9f, teammate = false)
        )
    )
    @Test fun speedAndDistanceComeFromTrackedMotionAndCoordinates() {
        val result = observeAgility(frame())!!
        assertEquals(50f, result.speed, 0.001f)
        // No58-D6: distance is perspective-corrected (dy 4 * 1.6 = 6.4),
        // so hypot(3, 6.4) = 7.069, not the raw 5. The raw 5 was the V3 pin.
        assertEquals(7.069f, result.distance, 0.01f)
        // Carrier (30,40) runs straight at the stationary defender, so the
        // gap closes at the full 50 px/s.
        assertEquals(50f, result.closingSpeed, 0.001f)
        assertEquals(0f, result.oppVelocityXPerSecond, 0f)
        assertEquals(0f, result.oppVelocityYPerSecond, 0f)
    }
    @Test fun confidenceAndDensityDoNotPoseAsMotionOrDistance() {
        val first = observeAgility(frame())!!
        val next = observeAgility(frame().copy(bestLaneConfidence = .99f, defenderDensity = .99f, confidence = .5f))!!
        assertEquals(first.speed, next.speed, 0f); assertEquals(first.distance, next.distance, 0f)
        assertEquals(.5f, next.confidence, 0f)
    }
    @Test fun missingCarrierOpponentOrMotionProducesNoInventedInputs() {
        val f = frame(); assertNull(observeAgility(f.copy(ownerId = null)))
        assertNull(observeAgility(f.copy(entities = f.entities.filter { it.teammate })))
        assertNull(observeAgility(f.copy(entities = listOf(f.entities[0].copy(velocityXPerSecond = Float.NaN), f.entities[1]))))
        assertNull(observeAgility(f.copy(entities = listOf(f.entities[0].copy(velocityYPerSecond = Float.NaN), f.entities[1]))))
    }
    @Test fun turnComesFromHeadingAndRequestedDirection() {
        val f = frame(); val straight = observeAgility(f)!!; assertEquals(0f, straight.turnIntensity, .001f)
        // Heading atan2(40,30) = 53.13 degrees.
        assertEquals(53.13f, straight.directionDegrees, 0.01f)
        val turning = observeAgility(f.copy(passTargetId = 3, passTargetX = -10f, passTargetY = -20f))!!
        assertTrue(turning.turnIntensity > .99f)
        // Desired atan2(-40,-30) = -126.87 degrees: a ~180 degree turn.
        assertEquals(-126.87f, turning.directionDegrees, 0.01f)
    }
    @Test fun referenceSpeedIsTheCaptureDiagonal() {
        val result = observeAgility(frame())!!
        assertEquals(701.815f, result.referenceSpeed, 0.02f)
    }
    @Test fun missingBallOrCaptureProducesNoObservation() {
        val f = frame()
        assertNull(observeAgility(f.copy(hasBall = false)))
        assertNull(observeAgility(f.copy(captureWidth = 0f)))
        assertNull(observeAgility(f.copy(confidence = 0f)))
    }
    @Test fun threatSelectionPicksClosingDefenderOverNearerDrifter() {
        // Carrier stationary at (20,20). A: nearer (raw 6) but drifting away
        // at +30 px/s (closing -30, threat 6 + 7.5 = 13.5). B: farther
        // (raw 12) but sprinting at the carrier at 40 px/s (closing 40,
        // threat 19.2 - 10 = 9.2). The old nearest rule would pick A; the
        // threat rule must pick B.
        val carrier = frame().entities[0].copy(velocityXPerSecond = 0f, velocityYPerSecond = 0f)
        val nearDrifter = RuntimeEntity(id = 2, x = 26f, y = 20f, velocityX = 0f, velocityY = 0f, confidence = .9f, teammate = false, velocityXPerSecond = 30f, velocityYPerSecond = 0f)
        val farCloser = RuntimeEntity(id = 3, x = 20f, y = 32f, velocityX = 0f, velocityY = 0f, confidence = .9f, teammate = false, velocityXPerSecond = 0f, velocityYPerSecond = -40f)
        val result = observeAgility(frame().copy(entities = listOf(carrier, nearDrifter, farCloser)))!!
        assertEquals(20f, result.opponentX, 0f); assertEquals(32f, result.opponentY, 0f)
        assertEquals(19.2f, result.distance, 0.01f)
        assertEquals(40f, result.closingSpeed, 0.001f)
        assertEquals(-40f, result.oppVelocityYPerSecond, 0f)
    }
}
