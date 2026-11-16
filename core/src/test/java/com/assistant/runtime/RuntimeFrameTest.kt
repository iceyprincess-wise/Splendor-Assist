package com.assistant.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * RF-D10: first tests for RuntimeFrame's own logic - ZoneDistribution
 * zones/balance, EngineContribution.weight, goalWidth, and the trusted
 * gate (RF-D1/RF-D2). Two GameplayRegressionTest behaviours are pinned
 * here as well: a NaN ball velocity is untrusted, and a panic frame
 * stays trusted (panic is handled by the contributors, not the gate).
 * RuntimeFrame has no Android imports, so this runs on the plain JVM.
 */
class RuntimeFrameTest {

    private fun frame() = RuntimeFrame(1, 1, false, 100f, 50f, 6f, 0f,
        2, 1, 0, 0, 0f, 0f, 0f, 0.5f, ZoneDistribution(), 0.9f, true, false,
        entities = listOf(RuntimeEntity(1, 110f, 50f, 0f, 0f, 0.9f, true),
            RuntimeEntity(2, 100f, 50f, 1f, 0f, 0.9f, false)),
        ownerId = 2, defenderId = 1, attackDirection = 1, captureWidth = 640f, captureHeight = 288f)

    private fun contribution(authority: Float, confidence: Float) =
        EngineContribution("test", ActionClass.PASS, 0f, 0f, authority, confidence, 60L)

    // ZoneDistribution

    @Test fun balanceInEmptyZonesIsZero() {
        assertEquals(0f, ZoneDistribution().balanceIn(0), 0f)
        assertEquals(0f, ZoneDistribution().balanceIn(1), 0f)
        assertEquals(0f, ZoneDistribution().balanceIn(2), 0f)
    }

    @Test fun balanceInStaysWithinMinusOneToOne() {
        val zones = ZoneDistribution(leftOurs = 5, leftTheirs = 0, midOurs = 0, midTheirs = 4,
            rightOurs = 1, rightTheirs = 3)
        assertEquals(1f, zones.balanceIn(0), 0f)
        assertEquals(-1f, zones.balanceIn(1), 0f)
        assertEquals(-0.5f, zones.balanceIn(2), 0f)
    }

    @Test fun balanceInWithNegativeCountsDoesNotEscapeRange() {
        // Negative counts are not live data, but balance must never return
        // a value outside [-1, 1] or crash on a non-positive total.
        val zones = ZoneDistribution(leftOurs = -5, leftTheirs = 3, midOurs = 5, midTheirs = -3)
        val left = zones.balanceIn(0)
        assertTrue(left >= -1f && left <= 1f)
        val mid = zones.balanceIn(1)
        assertTrue(mid >= -1f && mid <= 1f)
    }

    @Test fun outOfRangeZoneReadsAsEmpty() {
        val zones = ZoneDistribution(leftOurs = 1, midOurs = 2, rightOurs = 3,
            leftTheirs = 4, midTheirs = 5, rightTheirs = 6)
        assertEquals(0, zones.oursIn(3))
        assertEquals(0, zones.theirsIn(3))
        assertEquals(0, zones.oursIn(-1))
        assertEquals(0, zones.theirsIn(99))
        assertEquals(0f, zones.balanceIn(7), 0f)
        assertEquals(3, zones.oursIn(2))
        assertEquals(6, zones.theirsIn(2))
    }

    // EngineContribution.weight

    @Test fun weightClampsAuthorityAndConfidence() {
        assertEquals(0.81f, contribution(0.9f, 0.9f).weight, 0.0001f)
        assertEquals(0f, contribution(-0.5f, 0.9f).weight, 0f)
        assertEquals(0f, contribution(0.9f, -1f).weight, 0f)
        assertEquals(1f, contribution(2f, 3f).weight, 0f)
        assertEquals(0f, contribution(0f, 0f).weight, 0f)
    }

    @Test fun weightWithNaNInputIsNaN() {
        // Float.NaN.coerceIn(0f, 1f) returns NaN, so the product is NaN -
        // the registry backstops it, but the weight value itself is NaN.
        assertTrue(contribution(Float.NaN, 0.9f).weight.isNaN())
        assertTrue(contribution(0.9f, Float.NaN).weight.isNaN())
    }

    @Test fun weightWithInfiniteInputClampsToOne() {
        assertEquals(0.9f, contribution(Float.POSITIVE_INFINITY, 0.9f).weight, 0f)
        assertEquals(0.9f, contribution(0.9f, Float.POSITIVE_INFINITY).weight, 0f)
    }

    // goalWidth

    @Test fun goalWidthOfNormalBox() {
        assertEquals(40f, frame().copy(goalLeftX = 130f, goalRightX = 170f).goalWidth, 0f)
    }

    @Test fun goalWidthOfInvertedBoxIsZero() {
        assertEquals(0f, frame().copy(goalLeftX = 170f, goalRightX = 130f).goalWidth, 0f)
    }

    @Test fun goalWidthOfEmptyBoxIsZero() {
        assertEquals(0f, frame().goalWidth, 0f)
    }

    // trusted gate

    @Test fun baseFrameIsTrusted() {
        assertTrue(frame().trusted)
    }

    @Test fun zeroCaptureSizeIsUntrusted() {
        assertFalse(frame().copy(captureWidth = 0f).trusted)
        assertFalse(frame().copy(captureHeight = 0f).trusted)
        assertFalse(frame().copy(captureWidth = 0f, captureHeight = 0f).trusted)
    }

    @Test fun nonFiniteCaptureSizeIsUntrusted() {
        assertFalse(frame().copy(captureWidth = Float.NaN).trusted)
        assertFalse(frame().copy(captureHeight = Float.POSITIVE_INFINITY).trusted)
    }

    @Test fun confidenceAboveOneIsUntrusted() {
        assertFalse(frame().copy(confidence = 1.5f).trusted)
        assertFalse(frame().copy(confidence = Float.NaN).trusted)
        assertTrue(frame().copy(confidence = 1f).trusted)
    }

    @Test fun nanEntityIsUntrusted() {
        val entities = frame().entities
        assertFalse(frame().copy(entities = listOf(entities[0].copy(x = Float.NaN), entities[1])).trusted)
        assertFalse(frame().copy(entities = listOf(entities[0], entities[1].copy(velocityY = Float.NaN))).trusted)
        assertFalse(frame().copy(entities = listOf(entities[0], entities[1].copy(confidence = Float.NaN))).trusted)
        assertFalse(frame().copy(entities = listOf(entities[0].copy(velocityXPerSecond = Float.NaN), entities[1])).trusted)
    }

    @Test fun infiniteEntityVelocityIsUntrusted() {
        val entities = frame().entities
        assertFalse(frame().copy(entities = listOf(entities[0].copy(velocityX = Float.POSITIVE_INFINITY), entities[1])).trusted)
    }

    @Test fun goalCoordsCheckedOnlyWhenGoalDetected() {
        val detected = frame().copy(goalDetected = true, goalLeftX = Float.NaN)
        assertFalse(detected.trusted)
        // Same NaN coords with the goal undetected stay trusted: coords
        // may be non-zero (or non-finite) when undetected - gate on
        // goalDetected, never on the coordinates (RF-D3).
        assertTrue(frame().copy(goalDetected = false, goalLeftX = Float.NaN).trusted)
        assertTrue(frame().copy(goalDetected = true, goalLeftX = 130f, goalRightX = 170f,
            goalTopY = 40f, goalBottomY = 60f, goalConfidence = 0.9f).trusted)
    }

    @Test fun nanBallVelocityIsUntrusted() {
        assertFalse(frame().copy(ballVelocityX = Float.NaN).trusted)
    }

    @Test fun panicFrameStaysTrusted() {
        assertTrue(frame().copy(panic = true).trusted)
    }
}
