package com.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// No58 V4: pins for the AgilityBurstLatch hysteresis. The latch is pure
// Kotlin (no Android / native dependency), so these run in the JVM harness.
class AgilityBurstLatchTest {

    @Test
    fun startsInactive() {
        val latch = AgilityBurstLatch()
        assertFalse(latch.isLatched)
        assertFalse(latch.wasLatchedBeforeUpdate)
    }

    @Test
    fun turnsOnAboveThreshold() {
        val latch = AgilityBurstLatch()
        assertTrue(latch.update(0.76f))
        assertTrue(latch.isLatched)
    }

    @Test
    fun staysOffAtThreshold() {
        // Strictly above 0.75 turns on; exactly 0.75 does not.
        val latch = AgilityBurstLatch()
        assertFalse(latch.update(0.75f))
        assertFalse(latch.isLatched)
    }

    @Test
    fun holdsInsideHysteresisBand() {
        // Once on, an intensity between OFF (0.5) and ON (0.75) holds the
        // latched state instead of chattering.
        val latch = AgilityBurstLatch()
        latch.update(0.9f)
        assertTrue(latch.update(0.6f))
        assertTrue(latch.isLatched)
    }

    @Test
    fun turnsOffBelowThreshold() {
        val latch = AgilityBurstLatch()
        latch.update(0.9f)
        assertFalse(latch.update(0.49f))
        assertFalse(latch.isLatched)
    }

    @Test
    fun staysOnAtOffThreshold() {
        // Exactly 0.5 does not release; strictly below 0.5 does.
        val latch = AgilityBurstLatch()
        latch.update(0.9f)
        assertTrue(latch.update(0.5f))
        assertTrue(latch.isLatched)
    }

    @Test
    fun nanLeavesStateUnchanged() {
        val latch = AgilityBurstLatch()
        assertFalse(latch.update(Float.NaN))
        assertFalse(latch.isLatched)
        latch.update(0.9f)
        assertTrue(latch.update(Float.NaN))
        assertTrue(latch.isLatched)
    }

    @Test
    fun wasLatchedBeforeUpdateTracksTransition() {
        // The contributor logs the burst trigger exactly once, on the
        // frame the latch turns on: before that frame it was off, after
        // it the flag reads true.
        val latch = AgilityBurstLatch()
        latch.update(0.9f)
        assertFalse(latch.wasLatchedBeforeUpdate)
        latch.update(0.9f)
        assertTrue(latch.wasLatchedBeforeUpdate)
    }

    @Test
    fun resetClearsBothFlags() {
        val latch = AgilityBurstLatch()
        latch.update(0.9f)
        latch.reset()
        assertFalse(latch.isLatched)
        assertFalse(latch.wasLatchedBeforeUpdate)
    }
}
