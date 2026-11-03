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
    @Test fun staleAndReorderedSamplesDoNotCreateVelocity() {
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
        assertFalse(CentralExecutionBus.submit(request.copy(actionId = "next")))
        CentralExecutionBus.complete(request.actionId)
        assertTrue(CentralExecutionBus.submit(request.copy(actionId = "next")))
        CentralExecutionBus.stop()
    }
    @Test fun stalePhaseDoesNotBlockFreshReplacementBeforeConsumption() {
        UiExecutionGate.reset(); CentralExecutionBus.stop(); CentralExecutionBus.start()
        val old = ExecutionRequest(ExecutionSource.SMART_ASSIST, 1, 1f, 1f, 2f, 2f, 50L, timestamp = System.currentTimeMillis()-1000L)
        assertTrue(CentralExecutionBus.submit(old))
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

}
