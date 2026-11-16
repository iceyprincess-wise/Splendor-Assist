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
    @Test fun priorityAndIndependentSourcesRemainAvailable() {
        val normal = request(); val high = request(source = ExecutionSource.INTERCEPTION)
        assertTrue(CentralExecutionBus.submit(normal)); assertTrue(CentralExecutionBus.submit(high))
        assertEquals(high, CentralExecutionBus.consume()); assertEquals(normal, CentralExecutionBus.consume())
    }
    @Test fun staleAtArrivalIsRejectedAndNeverReserves() {
        // A stale-at-arrival request is now rejected at submit (it used to
        // be accepted and only discarded by the next submit), so it can
        // never hold a reservation that blocks a fresh request.
        val old = ExecutionRequest(ExecutionSource.SMART_ASSIST, 0, 10f, 10f, 20f, 20f, 60L,
            createdAtNanos = System.nanoTime() - 1_000_000_000L)
        assertFalse(CentralExecutionBus.submit(old))
        assertEquals(0, CentralExecutionBus.pendingCount())
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
    @Test fun independentPhasesOfSameSourceRemainAvailable() {
        val p0 = request(phase = 0)
        val p1 = request(phase = 1)
        assertTrue(CentralExecutionBus.submit(p0))
        assertTrue(CentralExecutionBus.submit(p1))
        assertEquals(p0, CentralExecutionBus.consume())
        assertEquals(p1, CentralExecutionBus.consume())
    }
    @Test fun peekSourceReportsHighestPriorityPending() {
        val normal = request()
        val high = request(source = ExecutionSource.INTERCEPTION)
        assertTrue(CentralExecutionBus.submit(normal))
        assertEquals(ExecutionSource.SMART_ASSIST, CentralExecutionBus.peekSource())
        assertTrue(CentralExecutionBus.submit(high))
        assertEquals(ExecutionSource.INTERCEPTION, CentralExecutionBus.peekSource())
        assertEquals(high, CentralExecutionBus.consume())
        assertEquals(ExecutionSource.SMART_ASSIST, CentralExecutionBus.peekSource())
    }
    @Test fun invalidRequestsAreRejected() {
        assertFalse(CentralExecutionBus.submit(request().copy(phase = -1)))
        assertFalse(CentralExecutionBus.submit(request().copy(duration = 0L)))
        assertFalse(CentralExecutionBus.submit(request().copy(startX = Float.POSITIVE_INFINITY)))
        assertFalse(CentralExecutionBus.submit(request().copy(endY = Float.NaN)))
        assertEquals(0, CentralExecutionBus.pendingCount())
    }
    @Test fun ringRejectsAtCapacityAndRecovers() {
        for (i in 0 until 1024) {
            assertTrue(CentralExecutionBus.submit(request(phase = i)))
        }
        assertFalse(CentralExecutionBus.submit(request(phase = 1024)))
        assertEquals(1024, CentralExecutionBus.pendingCount())
        assertNotNull(CentralExecutionBus.consume())
        assertTrue(CentralExecutionBus.submit(request(phase = 1024)))
        assertEquals(1024, CentralExecutionBus.pendingCount())
    }
    @Test fun busFlagsTrackAcceptanceAndConsumption() {
        val r = request()
        assertTrue(CentralExecutionBus.submit(r))
        assertTrue(r.busAccepted)
        assertEquals(r, CentralExecutionBus.consume())
        assertTrue(r.busConsumed)
    }
}
