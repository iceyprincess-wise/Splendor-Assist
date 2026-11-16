package com.assistant.runtime

import com.assistant.execution.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PredictionPolicyTest {
    @Test fun projectionUsesMeasuredTimeAt20Fps() {
        val p = PredictiveState()
        p.project(1, 10f, 10f, 1_000_000_000L, 50f, 640f, 288f)
        val next = p.project(1, 15f, 10f, 1_050_000_000L, 50f, 640f, 288f)
        assertEquals(100f, next.vxPerSecond, 0.01f); assertEquals(20f, next.x, 0.01f)
    }
    @Test fun staleReorderedAndOutdatedSamplesDoNotCreateVelocity() {
        val p = PredictiveState(); p.project(1, 10f, 10f, 100L, 50f, 640f, 288f)
        assertEquals(0f, p.project(1, 20f, 10f, 100L, 50f, 640f, 288f).vxPerSecond)
        assertEquals(0f, p.project(1, 30f, 10f, 500_000_000L, 50f, 640f, 288f).vxPerSecond)
    }
    @Test fun predictionCannotLeaveCapture() {
        val p = PredictiveState(); p.project(1, 600f, 10f, 1_000_000_000L, 100f, 640f, 288f)
        assertEquals(639f, p.project(1, 630f, 10f, 1_050_000_000L, 100f, 640f, 288f).x)
    }
    @Test fun dispatchCompensatesOnlyResidualLatencyInPitchSpace() {
        val motion = AimMotion(0f, 0f, 100f, 0f, 0f, 100f, 1_000_000_000L, 20f, 640f, 288f)
        val before = motion.endpoint(200f, 200f, 1_000_000_000L)!!
        val late = motion.endpoint(200f, 200f, 1_100_000_000L)!!
        assertEquals(200f, before.second); assertTrue(late.second > before.second)
        assertEquals(motion.endpoint(200f, 200f, 1_100_000_000L), motion.endpoint(200f, 200f, 9_000_000_000L))
    }
    @Test fun futureProjectionIsNotAppliedTwice() {
        val motion = AimMotion(0f, 0f, 100f, 0f, 0f, 100f, 2_000_000_000L, 20f, 640f, 288f)
        assertEquals(200f, motion.endpoint(200f, 200f, 1_000_000_000L)!!.second)
    }
    @Test fun macroPassRequiresDifferentKnownTeammate() {
        val p = OutcomePolicy(); p.dispatched("pass", ActionClass.PASS, 1, true, 1L)
        assertTrue(p.cooling(ActionClass.PASS, 50_000_000L))
        assertTrue(p.observe(1, true, true, 50_000_000L).isEmpty())
        assertTrue(p.observe(null, true, true, 50_000_000L).isEmpty())
        assertEquals(listOf("pass" to true), p.observe(2, true, true, 60_000_000L))
        assertFalse(p.cooling(ActionClass.PASS, 70_000_000L))
    }
    @Test fun failureAfter800msChangesConsumedConfidenceFactor() {
        val p = OutcomePolicy(); p.dispatched("shot", ActionClass.SHOT, 1, true, 1L)
        assertTrue(p.observe(2, true, true, 50_000_000L).isEmpty())
        assertEquals(listOf("shot" to false), p.observe(2, true, true, 801_000_001L))
        assertTrue(p.confidenceFactor(ActionClass.SHOT) < 1f)
    }
    @Test fun cancellationDoesNotPenalizePhysicsOrCalibration() {
        val p = OutcomePolicy(); p.dispatched("p", ActionClass.PASS, 1, true, 1L); p.cancel("p")
        assertTrue(p.observe(null, false, true, 900_000_000L).isEmpty())
        assertEquals(1f, p.confidenceFactor(ActionClass.PASS))
    }
    @Test fun maskedOverlayAndPaddingDoNotCreateNewGameFrame() {
        val a = ByteBuffer.allocate(24); val b = ByteBuffer.allocate(24)
        b.put(0, 5); b.put(11, 7)
        assertTrue(CaptureContent.same(a,b,2,2,12,4) { x,y -> x==0 && y==0 })
        assertFalse(CaptureContent.same(a,b,2,2,12,4) { _,_ -> false })
        b.put(16, 9)
        assertFalse(CaptureContent.same(a,b,2,2,12,4) { x,y -> x==0 && y==0 })
    }
    @Test fun uiGateFlushesQueueAndRejectsNewRequests() {
        UiExecutionGate.reset(); CentralExecutionBus.stop(); CentralExecutionBus.start()
        fun request() = ExecutionRequest(ExecutionSource.SMART_ASSIST, 0, 1f, 1f, 2f, 2f, 50L)
        assertTrue(CentralExecutionBus.submit(request()))
        UiExecutionGate.block("pause")
        assertTrue(UiExecutionGate.blocked())
        assertEquals("pause", UiExecutionGate.reason)
        assertEquals(0, CentralExecutionBus.pendingCount()); assertFalse(CentralExecutionBus.submit(request()))
        assertNull(CentralExecutionBus.consume()); UiExecutionGate.reset(); CentralExecutionBus.stop()
    }

    @Test fun preDispatchPixelsCannotConfirmAnOutcome() {
        val p = OutcomePolicy(); p.dispatched("p", ActionClass.PASS, 1, true, 100_000_000L)
        assertTrue(p.observe(2, true, true, 150_000_000L, 99_000_000L).isEmpty())
        assertEquals(listOf("p" to true), p.observe(2, true, true, 160_000_000L, 151_000_000L))
    }
    @Test fun busCompensationRetainsTypedActionAndReservation() {
        UiExecutionGate.reset(); CentralExecutionBus.stop(); CentralExecutionBus.start()
        val motion = AimMotion(0f, 0f, 100f, 0f, 0f, 100f, System.nanoTime()-50_000_000L, 20f, 640f, 288f)
        val request = ExecutionRequest(ExecutionSource.SMART_ASSIST, 0, 200f, 200f, 220f, 200f, 50L,
            aimMotion = motion, actionClass = ActionClass.PASS)
        assertTrue(CentralExecutionBus.submit(request))
        val consumed = CentralExecutionBus.consume()!!
        assertTrue(consumed.endY > 200f); assertEquals(ActionClass.PASS, consumed.actionClass)
        assertEquals(request.actionId, consumed.actionId)
        assertFalse(CentralExecutionBus.submit(request.copy(actionId = "next", createdAtNanos = System.nanoTime())))
        CentralExecutionBus.complete(request.actionId)
        assertTrue(CentralExecutionBus.submit(request.copy(actionId = "next", createdAtNanos = System.nanoTime())))
        CentralExecutionBus.stop()
    }
    @Test fun stalePhaseDoesNotBlockFreshReplacementBeforeConsumption() {
        UiExecutionGate.reset(); CentralExecutionBus.stop(); CentralExecutionBus.start()
        val old = ExecutionRequest(ExecutionSource.SMART_ASSIST, 1, 1f, 1f, 2f, 2f, 50L, timestamp = System.currentTimeMillis()-1000L, createdAtNanos = System.nanoTime() - 1_000_000_000L)
        assertFalse(CentralExecutionBus.submit(old))
        val fresh = old.copy(timestamp = System.currentTimeMillis(), createdAtNanos = System.nanoTime(), actionId = "fresh")
        assertTrue(CentralExecutionBus.submit(fresh)); assertEquals("fresh",CentralExecutionBus.consume()!!.actionId)
        CentralExecutionBus.stop()
    }
    @Test fun macroChangeAfterDeadlineIsStillFailure() {
        val p=OutcomePolicy();p.dispatched("p",ActionClass.PASS,1,true,1L)
        assertEquals(listOf("p" to false),p.observe(2,true,true,900_000_000L))
    }
    @Test fun uiInterruptionAbandonsOutcomesWithoutTrainingOnMenus() {
        val p=OutcomePolicy();p.dispatched("p",ActionClass.PASS,1,true,1L);p.abandonPending()
        assertTrue(p.observe(2,true,true,900_000_000L).isEmpty())
        assertEquals(1f,p.confidenceFactor(ActionClass.PASS))
    }
    @Test fun macroSucceedsAtExactly800msDeadline() {
        val p = OutcomePolicy(); p.dispatched("p", ActionClass.PASS, 1, true, 1L)
        assertEquals(listOf("p" to true), p.observe(2, true, true, 800_000_001L))
    }
    @Test fun coolingWindowEndsAt800ms() {
        val p = OutcomePolicy(); p.dispatched("p", ActionClass.PASS, 1, true, 1L)
        assertTrue(p.cooling(ActionClass.PASS, 800_000_001L))
        assertFalse(p.cooling(ActionClass.PASS, 800_000_002L))
    }
    @Test fun macroTooEarlyStaysPendingThenSucceeds() {
        val p = OutcomePolicy(); p.dispatched("p", ActionClass.PASS, 1, true, 1L)
        assertTrue(p.observe(2, true, true, 29_000_001L).isEmpty())
        assertEquals(listOf("p" to true), p.observe(2, true, true, 60_000_001L))
    }
    @Test fun moveAndNoneDispatchesAreIgnored() {
        val p = OutcomePolicy(); p.dispatched("m", ActionClass.MOVE, 1, true, 1L)
        p.dispatched("n", ActionClass.NONE, 1, true, 1L)
        assertFalse(p.cooling(ActionClass.MOVE, 50_000_000L))
        assertFalse(p.cooling(ActionClass.NONE, 50_000_000L))
        assertTrue(p.observe(2, true, true, 60_000_000L).isEmpty())
        assertEquals(1f, p.confidenceFactor(ActionClass.MOVE))
        assertEquals(1f, p.confidenceFactor(ActionClass.NONE))
    }
    @Test fun defendMacroSucceedsWhenBallIsWon() {
        val p = OutcomePolicy(); p.dispatched("d", ActionClass.DEFEND, 1, false, 1L)
        assertEquals(listOf("d" to true), p.observe(2, true, true, 60_000_001L))
    }
    @Test fun keeperMacroSucceedsWhenBallIsWon() {
        val p = OutcomePolicy(); p.dispatched("k", ActionClass.KEEPER, 1, false, 1L)
        assertEquals(listOf("k" to true), p.observe(2, true, true, 60_000_001L))
    }
    @Test fun crossMacroSucceedsOnDifferentTeammate() {
        val p = OutcomePolicy(); p.dispatched("c", ActionClass.CROSS, 1, true, 1L)
        assertEquals(listOf("c" to true), p.observe(2, true, true, 60_000_001L))
    }
    @Test fun repeatedFailuresStopAtReliabilityFloor() {
        val p = OutcomePolicy()
        repeat(9) { i ->
            p.dispatched("s$i", ActionClass.SHOT, 1, true, 1L)
            assertEquals(listOf("s$i" to false), p.observe(2, true, true, 900_000_000L))
        }
        assertEquals(0.6f, p.confidenceFactor(ActionClass.SHOT), 0.001f)
    }
    @Test fun leadBeyond100msIsClamped() {
        val p = PredictiveState()
        p.project(1, 10f, 10f, 1_000_000_000L, 500f, 640f, 288f)
        val next = p.project(1, 15f, 10f, 1_050_000_000L, 500f, 640f, 288f)
        assertEquals(100f, next.vxPerSecond, 0.01f); assertEquals(25f, next.x, 0.01f)
    }
    @Test fun aimEndpointReturnsNullOnInvalidMotion() {
        val zeroRadius = AimMotion(0f, 0f, 100f, 0f, 0f, 100f, 1_000_000_000L, 0f, 640f, 288f)
        assertNull(zeroRadius.endpoint(200f, 200f, 1_000_000_000L))
        val nanTarget = AimMotion(0f, 0f, Float.NaN, 0f, 0f, 100f, 1_000_000_000L, 20f, 640f, 288f)
        assertNull(nanTarget.endpoint(200f, 200f, 1_000_000_000L))
    }
    @Test fun captureContentRejectsInvalidGeometry() {
        val a = ByteBuffer.allocate(24); val b = ByteBuffer.allocate(24)
        assertFalse(CaptureContent.same(a, b, 0, 2, 12, 4) { _, _ -> false })
        assertFalse(CaptureContent.same(a, b, 2, 2, 12, 2) { _, _ -> false })
    }
    @Test fun predictiveStateResetClearsHistory() {
        val p = PredictiveState()
        p.project(1, 10f, 10f, 1_000_000_000L, 50f, 640f, 288f)
        p.reset()
        val next = p.project(1, 15f, 10f, 1_050_000_000L, 50f, 640f, 288f)
        assertEquals(0f, next.vxPerSecond, 0.01f); assertEquals(15f, next.x, 0.01f)
    }
    @Test fun predictiveStateRetainDropsForgottenIds() {
        val p = PredictiveState()
        p.project(1, 10f, 10f, 1_000_000_000L, 50f, 640f, 288f)
        p.project(2, 10f, 10f, 1_000_000_000L, 50f, 640f, 288f)
        p.retain(setOf(2))
        val dropped = p.project(1, 15f, 10f, 1_050_000_000L, 50f, 640f, 288f)
        assertEquals(0f, dropped.vxPerSecond, 0.01f)
        val kept = p.project(2, 15f, 10f, 1_050_000_000L, 50f, 640f, 288f)
        assertEquals(100f, kept.vxPerSecond, 0.01f)
    }
    // B24-01 pin: a non-finite start point must reject here, because the
    // bus requestIsValid check never sees it (the compensation path
    // bypasses submit-time validation).
    @Test fun aimEndpointRejectsNonFiniteStart() {
        val motion = AimMotion(0f, 0f, 100f, 0f, 0f, 100f, 1_000_000_000L, 20f, 640f, 288f)
        assertNull(motion.endpoint(Float.NaN, 200f, 1_000_000_000L))
        assertNull(motion.endpoint(200f, Float.POSITIVE_INFINITY, 1_000_000_000L))
        assertNotNull(motion.endpoint(200f, 200f, 1_000_000_000L))
    }
    // B24-03 pin: the 800ms deadline judges the PIXELS, not the verify
    // call. Pixels captured 790ms after dispatch are in-window even
    // though the verify call runs at 810ms.
    @Test fun macroJudgedByCaptureTimeNotVerifyTime() {
        val p = OutcomePolicy(); p.dispatched("p", ActionClass.PASS, 1, true, 1L)
        assertEquals(listOf("p" to true), p.observe(2, true, true, 810_000_001L, 790_000_001L))
    }
    // B24-04 pin: a sub-pixel frame (0 < size < 1) passes PredictiveState
    // and must pass AimMotion too; the point clamps to 0f instead of null.
    @Test fun aimEndpointAcceptsSubPixelFrameAndClamps() {
        val motion = AimMotion(0f, 0f, 100f, 0f, 0f, 100f, 1_000_000_000L, 20f, 0.5f, 0.5f)
        val point = motion.endpoint(200f, 200f, 1_000_000_000L)
        assertNotNull(point)
        assertEquals(0f, point!!.first, 0.001f); assertEquals(0f, point.second, 0.001f)
    }

}
