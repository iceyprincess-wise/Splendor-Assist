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
    // D-26-03 pin: the STUTTER low ring exists and orders after the
    // medium ring. D-27-STUTTER wired the producer: BurstForensicsEngine
    // re-asserts the most recent fresh MOVE stroke through
    // HybridExecutionTerminal.route() on a SEIZURE transition, so the
    // low ring is live in production now, not test-pinned only. The
    // test below pins the exact request shape that producer submits.
    @Test fun lowRingOrdersAfterMediumRing() {
        val low = request(source = ExecutionSource.STUTTER)
        val med = request()
        assertTrue(CentralExecutionBus.submit(low)); assertTrue(CentralExecutionBus.submit(med))
        assertEquals(med, CentralExecutionBus.consume()); assertEquals(low, CentralExecutionBus.consume())
    }
    // D-27-STUTTER pin: the exact request shape the producer submits
    // (BurstForensicsEngine.tryReassertMove) — STUTTER source, MOVE
    // phase/actionClass, copied MOVE coords, no AimMotion (the
    // re-assert re-issues dispatched coords; a stale re-prediction
    // must not move the endpoint). It must submit and consume after
    // the medium ring, like any low-ring request.
    @Test fun stutterReassertShapeSubmitsAndConsumesAfterMedium() {
        val med = request()
        val reassert = ExecutionRequest(
            ExecutionSource.STUTTER, ActionClass.MOVE.ordinal,
            250f, 550f, 300f, 550f, 30L, actionClass = ActionClass.MOVE
        )
        assertEquals(ActionClass.MOVE.ordinal, reassert.phase)
        assertNull(reassert.aimMotion)
        assertTrue(CentralExecutionBus.submit(reassert))
        assertTrue(CentralExecutionBus.submit(med))
        assertEquals(med, CentralExecutionBus.consume())
        assertEquals(reassert, CentralExecutionBus.consume())
        assertTrue(reassert.busAccepted)
        assertTrue(reassert.busConsumed)
    }
    // D-26-04 pin: degenerate motion (radius 0) cannot be compensated;
    // the bus executes the raw request and counts it under
    // aimNullCompensations, not invalidDrops. Counter deltas are used
    // because the bus counters are cumulative across tests in one run.
    @Test fun aimNullConsumeExecutesUncompensatedAndCountsSeparately() {
        CentralExecutionBus.start() // force a statistics refresh past the 100ms throttle
        val before = CentralExecutionBus.statistics.value
        val motion = AimMotion(0f, 0f, 100f, 0f, 0f, 100f, System.nanoTime(), 0f, 640f, 288f)
        val request = ExecutionRequest(ExecutionSource.SMART_ASSIST, 0, 200f, 200f, 220f, 200f, 50L, aimMotion = motion)
        assertTrue(CentralExecutionBus.submit(request))
        val consumed = CentralExecutionBus.consume()!!
        assertEquals(220f, consumed.endX, 0.001f); assertEquals(200f, consumed.endY, 0.001f)
        CentralExecutionBus.start()
        val after = CentralExecutionBus.statistics.value
        assertEquals(before.aimNullCompensations + 1, after.aimNullCompensations)
        assertEquals(before.invalidDrops, after.invalidDrops)
    }
}
