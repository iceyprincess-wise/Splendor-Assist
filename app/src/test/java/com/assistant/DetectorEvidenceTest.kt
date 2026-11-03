package com.assistant

import org.junit.Test
import org.junit.Assert.*

class DetectorEvidenceTest {
    private fun blob(x: Int, r: Float, g: Float, b: Float) = ConnectedComponentEngine.Blob(x, 20, x+3, 28, 30, r,g,b)
    @Test fun unknownAndKeeperBlobsAreNotOpponents() {
        val result = PlayerDetector.detect(listOf(blob(20, 100f,100f,100f),blob(60,0f,220f,0f)))
        assertEquals(0, result.playerCount)
    }
    @Test fun opponentsInContactAreNotSuppressedAsSamePlayer() {
        val result = PlayerDetector.detect(listOf(blob(20,255f,0f,0f),blob(22,0f,0f,255f)))
        assertEquals(2,result.playerCount)
    }
    @Test fun publishedDetectionsRemainStableOnNextFrame() {
        val first = PlayerDetector.detect(listOf(blob(20,255f,0f,0f)))
        val before = first.detections.first().x
        PlayerDetector.detect(listOf(blob(100,255f,0f,0f)))
        assertEquals(before,first.detections.first().x)
    }
    @Test fun candidateConfidenceDoesNotFabricatePixelMeasurements() {
        val result = BallDetector.detect(BallCandidate(20f,20f,2f,17,1f,0.8f))
        assertEquals(0,result.searchPixels); assertEquals(17,result.matchedPixels)
        assertFalse(BallDetector.detect(null).observed)
    }
    @Test fun nanCandidateIsNotFreshDetection() {
        assertFalse(BallDetector.detect(BallCandidate(Float.NaN,20f,2f,17,1f,0.8f)).observed)
    }

    @Test fun scannerHonorsPaddingAndRgbPixelStride() {
        val b = java.nio.ByteBuffer.allocate(16)
        for (i in 0 until b.limit()) b.put(i, 255.toByte())
        val f = FrameNormalizer.NormalizedFrame(b,2,2,8,3,FrameNormalizer.FrameMetadata(1,0))
        val result = FrameScanner.scan(f)
        assertEquals(4,result.count)
        assertEquals(listOf(0,1,0,1), (0 until result.count).map { ((result.data[it] shr 40) and 65535).toInt() })
        assertEquals(listOf(0,0,1,1), (0 until result.count).map { ((result.data[it] shr 24) and 65535).toInt() })
    }
    @Test fun scannerRejectsTruncatedLayout() {
        val f = FrameNormalizer.NormalizedFrame(java.nio.ByteBuffer.allocate(10),2,2,8,3,FrameNormalizer.FrameMetadata(1,0))
        assertEquals(0,FrameScanner.scan(f).count)
    }
    @Test fun firstObservationAndReacquisitionDoNotCreatePhantomVelocity() {
        MotionTracker.update(BallDetectionResult(false,0f,0f,0f,0f,0,0))
        val first=MotionTracker.update(BallDetectionResult(true,100f,100f,2f,0.8f,0,0))
        assertEquals(0f,first.speed)
        assertTrue(MotionTracker.update(BallDetectionResult(true,110f,100f,2f,0.8f,0,0)).speed > 0f)
        MotionTracker.update(BallDetectionResult(true,110f,100f,2f,0.3f,0,0,observed=false))
        assertEquals(0f,MotionTracker.update(BallDetectionResult(true,200f,100f,2f,0.8f,0,0)).speed)
    }
    @Test fun whiteJerseyAreaDoesNotProvePitchGeometry() {
        val result=FieldLineDetector.detect(listOf(ConnectedComponentEngine.Blob(10,10,30,30,400,255f,255f,255f)))
        assertFalse(result.penaltyAreaDetected);assertFalse(result.goalAreaDetected)
        assertFalse(result.touchLinesDetected);assertFalse(result.centerCircleDetected)
    }
    @Test fun controlProbeUsesLimitInsteadOfCapacity() {
        ControlMappingTrainer.reset()
        val b=java.nio.ByteBuffer.allocate(400);b.limit(1)
        ControlMappingTrainer.observe(b,10,10,40)
        assertEquals(ControlMappingTrainer.UiMode.UNKNOWN,ControlMappingTrainer.uiMode)
    }
}
