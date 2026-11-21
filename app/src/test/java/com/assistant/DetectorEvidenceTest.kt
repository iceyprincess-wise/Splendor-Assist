package com.assistant

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class DetectorEvidenceTest {
    @Before fun setUp() {
        // No57-D6: MotionTracker and BallDetector are stateful singletons
        // with no reset before this round; the motion test self-seeded with
        // a false update and the ball tests only asserted fields that do not
        // depend on prior state. Reset explicitly so JUnit's hash method
        // order can never flip a result.
        MotionTracker.reset()
        BallDetector.reset()
        ControlMappingTrainer.reset()
    }

    private fun blob(x: Int, r: Float, g: Float, b: Float) = ConnectedComponentEngine.Blob(x, 20, x+3, 28, 30, r,g,b)
    @Test fun unknownAndKeeperBlobsAreNotOpponents() {
        val result = PlayerDetector.detect(listOf(blob(20, 100f,100f,100f),blob(60,0f,220f,0f)))
        assertEquals(0, result.playerCount)
    }
    @Test fun differentTeamBlobsInContactAreBothKept() {
        // No57-D3: renamed — the old name said "opponents in contact", but
        // the fixture is one USER (yellow — 6a: red is no longer a team
        // colour, the old red->USER rule is removed) and one OPPONENT
        // (blue). What this pins is the NMS rule: suppression only fires
        // between SAME-team detections, so cross-team blobs 2px apart are
        // both kept.
        val result = PlayerDetector.detect(listOf(blob(20,179f,150f,42f),blob(22,0f,0f,255f)))
        assertEquals(2,result.playerCount)
    }
    @Test fun sameTeamBlobsInContactAreMergedAsOnePlayer() {
        // No57-D3: the companion pin — two OPPONENT (blue) blobs 2px apart
        // are the same player split by the detector, so NMS keeps one: the
        // first on the confidence tie (Arrays.sort is stable). If distinct
        // close opponents ever need separate tracks, the NMS radius
        // (3..12px from captureHeightOrFallback) is the knob, not this test.
        val result = PlayerDetector.detect(listOf(blob(20,0f,0f,255f),blob(22,0f,0f,255f)))
        assertEquals(1,result.playerCount)
        assertEquals(21.5f,result.detections.first().x,0f)
    }
    @Test fun publishedDetectionsRemainStableOnNextFrame() {
        // No57-D1: the old test compared `first` with itself and discarded
        // the second detect()'s return, so it could never fail — detect()
        // builds fresh PlayerDetection objects per call. Capture both
        // results: the second must track the new frame (101.5) while the
        // first still reads the old one (21.5). (6a: the fixture was red;
        // red is no longer a team colour, so the blob is now the measured
        // Dortmund yellow (179,150,42). Geometry and assertions unchanged.)
        val first = PlayerDetector.detect(listOf(blob(20,179f,150f,42f)))
        val second = PlayerDetector.detect(listOf(blob(100,179f,150f,42f)))
        assertEquals(21.5f,first.detections.first().x,0f)
        assertEquals(101.5f,second.detections.first().x,0f)
    }
    @Test fun candidateConfidenceDoesNotFabricatePixelMeasurements() {
        // Item 15 (Letter B): the old searchPixels/matchedPixels fields
        // are deleted — searchPixels was permanently 0 (no search area
        // exists) and matchedPixels duplicated the candidate's pixelCount.
        // What this pins now is the honest remainder: a candidate above
        // the confidence floor is a real detection with its measured
        // position and confidence, and a null candidate is not observed.
        val result = BallDetector.detect(BallCandidate(20f,20f,2f,17,0.8f))
        assertTrue(result.detected)
        assertEquals(20f, result.x, 0f)
        assertEquals(20f, result.y, 0f)
        assertEquals(0.8f, result.confidence, 0f)
        assertTrue(result.observed)
        assertFalse(BallDetector.detect(null).observed)
    }
    @Test fun nanCandidateIsNotFreshDetection() {
        assertFalse(BallDetector.detect(BallCandidate(Float.NaN,20f,2f,17,0.8f)).observed)
    }

    @Test fun scannerHonorsPaddingAndRgbPixelStride() {
        // No57-D4: the old fixture filled all 16 bytes with 255, so a
        // scanner that IGNORED rowStride or pixelStride read the same white
        // pixels at the same packed coordinates — count and coords were
        // identical either way. Now row 0 is yellow, row 1 is blue, and the
        // buffer is exactly the 14 bytes `required` allows: a stride-6
        // reader hits the zero padding at byte 6 and loses a pixel
        // (count 3), and the packed RGB pins the channel order. (6a: row 0
        // was red — red is no longer a team colour, so the scanner drops
        // red pixels at the gate; the row is now the measured yellow.)
        val b = java.nio.ByteBuffer.allocate(14)
        b.put(0, 179.toByte()); b.put(1, 150.toByte()); b.put(2, 42.toByte())
        b.put(3, 179.toByte()); b.put(4, 150.toByte()); b.put(5, 42.toByte())
        b.put(10, 255.toByte()); b.put(13, 255.toByte())
        val f = FrameNormalizer.NormalizedFrame(b,2,2,8,3,FrameNormalizer.FrameMetadata(1,0))
        val result = FrameScanner.scan(f)
        assertEquals(4,result.count)
        assertEquals(listOf(0,1,0,1), (0 until result.count).map { ((result.data[it] shr 40) and 65535).toInt() })
        assertEquals(listOf(0,0,1,1), (0 until result.count).map { ((result.data[it] shr 24) and 65535).toInt() })
        assertEquals(listOf(179,179,0,0), (0 until result.count).map { ((result.data[it] shr 16) and 255).toInt() })
        assertEquals(listOf(150,150,0,0), (0 until result.count).map { ((result.data[it] shr 8) and 255).toInt() })
        assertEquals(listOf(42,42,255,255), (0 until result.count).map { (result.data[it] and 255).toInt() })
    }
    @Test fun scannerRejectsTruncatedLayout() {
        val f = FrameNormalizer.NormalizedFrame(java.nio.ByteBuffer.allocate(10),2,2,8,3,FrameNormalizer.FrameMetadata(1,0))
        assertEquals(0,FrameScanner.scan(f).count)
    }
    @Test fun firstObservationAndReacquisitionDoNotCreatePhantomVelocity() {
        MotionTracker.update(BallDetectionResult(false,0f,0f,0f))
        val first=MotionTracker.update(BallDetectionResult(true,100f,100f,0.8f))
        assertEquals(0f,first.speed)
        assertTrue(MotionTracker.update(BallDetectionResult(true,110f,100f,0.8f)).speed > 0f)
        MotionTracker.update(BallDetectionResult(true,110f,100f,0.3f,observed=false))
        assertEquals(0f,MotionTracker.update(BallDetectionResult(true,200f,100f,0.8f)).speed)
    }
    @Test fun whiteJerseyAreaDoesNotProvePitchGeometry() {
        val result=FieldLineDetector.detect(listOf(ConnectedComponentEngine.Blob(10,10,30,30,400,255f,255f,255f)))
        assertFalse(result.penaltyAreaDetected);assertFalse(result.goalAreaDetected)
        assertFalse(result.touchLinesDetected);assertFalse(result.centerCircleDetected)
        // No57-D5: the appearance score IS preserved even though the
        // geometry is unknown — 400 white pixels is exactly the 400px
        // normaliser, so confidence is 1.0. Pin it, or a future change
        // that zeroes the score would pass silently.
        assertEquals(1f,result.confidence,0f)
    }
    @Test fun controlProbeUsesLimitInsteadOfCapacity() {
        // No57-D2: the old fixture was an all-zero buffer, so a probe that
        // read by CAPACITY (400) instead of LIMIT (1) still saw zeros and
        // still answered UNKNOWN — the test could not tell the two apart.
        // Now the buffer carries a grey TAB pixel and a blue BACK pixel at
        // the trainer's own probe positions: capacity reads would classify
        // SETTINGS, limit reads must see nothing. Positions for a 10x10
        // frame, rowStride 40, pixelStride 4: TAB (0.500, 0.131) -> (5,1)
        // -> byte 60; BACK (0.157, 0.819) -> (1,8) -> byte 324.
        val b=java.nio.ByteBuffer.allocate(400)
        b.put(60,150.toByte());b.put(61,150.toByte());b.put(62,150.toByte())
        b.put(324,0.toByte());b.put(325,0.toByte());b.put(326,200.toByte())
        b.limit(1)
        ControlMappingTrainer.observe(b,10,10,40)
        assertEquals(ControlMappingTrainer.UiMode.UNKNOWN,ControlMappingTrainer.uiMode)
        assertEquals(0L,ControlMappingTrainer.mappingRuntimeSnapshot()["probesRead"])
        b.limit(400)
        ControlMappingTrainer.observe(b,10,10,40)
        assertEquals(ControlMappingTrainer.UiMode.SETTINGS,ControlMappingTrainer.uiMode)
    }
}
