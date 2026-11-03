package com.assistant.runtime

import com.assistant.execution.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExecutionBoundaryTest {
    @Before fun prepare() { CentralExecutionBus.stop(); CentralExecutionBus.start() }
    @After fun cleanup() { CentralExecutionBus.stop() }
    private fun request(phase: Int = 0, source: ExecutionSource = ExecutionSource.SMART_ASSIST) =
        ExecutionRequest(source, phase, 10f, 10f, 20f, 20f, 60L)
    @Test fun samePhaseCannotStackUntilCompletion() {
        val first = request()
        assertTrue(CentralExecutionBus.submit(first))
        assertFalse(CentralExecutionBus.submit(request()))
        assertEquals(first, CentralExecutionBus.consume())
        assertFalse(CentralExecutionBus.submit(request()))
        CentralExecutionBus.complete(first.actionId)
        assertTrue(CentralExecutionBus.submit(request()))
    }
    @Test fun completionOfOldActionDoesNotReleaseNewAction() {
        val old = request(); CentralExecutionBus.submit(old); CentralExecutionBus.consume()
        CentralExecutionBus.complete(old.actionId)
        val next = request(); assertTrue(CentralExecutionBus.submit(next))
        CentralExecutionBus.complete(old.actionId)
        assertFalse(CentralExecutionBus.submit(request()))
    }
    @Test fun priorityAndIndependentPhasesRemainAvailable() {
        val normal = request(); val high = request(source = ExecutionSource.INTERCEPTION)
        assertTrue(CentralExecutionBus.submit(normal)); assertTrue(CentralExecutionBus.submit(high))
        assertEquals(high, CentralExecutionBus.consume()); assertEquals(normal, CentralExecutionBus.consume())
    }
    @Test fun staleHeadReleasesReservation() {
        val old = request(); CentralExecutionBus.submit(old); old.timestamp -= 1000L
        assertNull(CentralExecutionBus.peekSource()); assertEquals(0, CentralExecutionBus.pendingCount())
        assertTrue(CentralExecutionBus.submit(request()))
    }
    @Test fun stopPurgesPreviousSession() {
        CentralExecutionBus.submit(request()); CentralExecutionBus.stop(); CentralExecutionBus.start()
        assertNull(CentralExecutionBus.consume()); assertTrue(CentralExecutionBus.submit(request()))
    }
    @Test fun invalidCoordinatesAreRejected() {
        assertFalse(CentralExecutionBus.submit(request().copy(endX = Float.NaN)))
        assertEquals(0, CentralExecutionBus.pendingCount())
    }
    @Test fun ringCanBeReusedAcrossSeveralWraps() {
        repeat(4096) {
            val r = request(); assertTrue(CentralExecutionBus.submit(r))
            assertEquals(r.actionId, CentralExecutionBus.consume()?.actionId)
            CentralExecutionBus.complete(r.actionId)
        }
        assertEquals(0, CentralExecutionBus.pendingCount())
    }
}
