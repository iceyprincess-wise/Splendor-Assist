package com.assistant

import com.assistant.vision.CameraProfile
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ForegroundSegmentationTest {
    // No56-D5: the keeper test sets the GLOBAL CameraProfile scale and
    // never restored it, so the 640x288 scale leaked into every test
    // class that ran later in the same JVM. Save/restore around every
    // test. GoalkeeperDetector.reset() in setUp clears the coasting
    // state (No56-D4) so no test depends on another test's frames.
    private var savedCaptureWidth = 0
    private var savedCaptureHeight = 0
    private var savedSourceWidth = 0
    private var savedSourceHeight = 0

    @Before fun setUp() {
        savedCaptureWidth = CameraProfile.captureWidth
        savedCaptureHeight = CameraProfile.captureHeight
        savedSourceWidth = CameraProfile.sourceWidth
        savedSourceHeight = CameraProfile.sourceHeight
        GoalkeeperDetector.reset()
    }

    @After fun tearDown() {
        CameraProfile.setCaptureScale(savedCaptureWidth, savedCaptureHeight, savedSourceWidth, savedSourceHeight)
    }

    @Test fun saturatedYellowAndBlueJerseysReachThePlayerDetector() {
        // (6a: was saturatedRedAndBlue — red is no longer a team colour,
        // the old red->USER rule is removed. The USER half is now the
        // measured Dortmund yellow (179,150,42).)
        val width=8;val height=8;val row=width*4+8;val b=ByteBuffer.allocate(row*height)
        for(y in 0 until height) for(x in 0 until width) {
            val i=y*row+x*4
            if(x<4) { b.put(i,179.toByte()); b.put(i+1,150.toByte()); b.put(i+2,42.toByte()) } else b.put(i+2,255.toByte())
        }
        // No56-D2: the 8 padding bytes per row used to be zero, which the
        // scanner never retains — so a scanner that IGNORED rowStride and
        // read rows back-to-back produced the identical 2 blobs, and this
        // test could not tell. Fill the padding green (kind 4): a
        // stride-ignoring scanner reads it as a third, green blob, while
        // the correct scanner never touches it.
        for(y in 0 until height) { b.put(y*row+33,255.toByte()); b.put(y*row+37,255.toByte()) }
        val f=FrameNormalizer.NormalizedFrame(b,width,height,row,4,FrameNormalizer.FrameMetadata(1))
        val blobs=ConnectedComponentEngine.extract(FrameScanner.scan(f))
        assertEquals(2,blobs.size)
        // No56-D1: pin the split location and the channel assignment, not
        // just the count — a yellow/blue byte-order swap in the scanner would
        // still yield 2 blobs and 2 players, with the teams exchanged.
        val sorted=blobs.sortedBy { it.minX }
        assertEquals(0,sorted[0].minX);assertEquals(3,sorted[0].maxX)
        assertEquals(179f,sorted[0].averageRed,0f);assertEquals(150f,sorted[0].averageGreen,0f);assertEquals(42f,sorted[0].averageBlue,0f)
        assertEquals(4,sorted[1].minX);assertEquals(7,sorted[1].maxX)
        assertEquals(0f,sorted[1].averageRed,0f);assertEquals(255f,sorted[1].averageBlue,0f)
        val detections=PlayerDetector.detect(blobs)
        assertEquals(2,detections.detections.size)
        assertTrue(detections.detections.first { it.x < 4f }.isUserTeam)
        assertFalse(detections.detections.first { it.x > 4f }.isUserTeam)
        assertEquals(1.5f,detections.detections.first { it.x < 4f }.x,0f)
        assertEquals(5.5f,detections.detections.first { it.x > 4f }.x,0f)
    }
    @Test fun colorBoundarySplitsYellowAndWhiteIntoTwoBlobs() {
        // No56-D3: renamed — the old name claimed a ball-vs-jersey split,
        // but no ball is ever identified here; what this pins is a
        // colour-boundary split (yellow kind 2 vs white kind 1) in the
        // connected-component pass. (6a: was red kind 2 — red is no longer
        // a team colour, so the USER side is now the measured yellow.)
        val b=ByteBuffer.allocate(8*4)
        for(x in 0 until 8) {
            if(x<4) { b.put(x*4,179.toByte()); b.put(x*4+1,150.toByte()); b.put(x*4+2,42.toByte()) }
            else { b.put(x*4,255.toByte()); b.put(x*4+1,255.toByte()); b.put(x*4+2,255.toByte()) }
        }
        val f=FrameNormalizer.NormalizedFrame(b,8,1,32,4,FrameNormalizer.FrameMetadata(1))
        assertEquals(2,ConnectedComponentEngine.extract(FrameScanner.scan(f)).size)
    }
    @Test fun pitchSizedGreenBlobIsNotKeeperEvidence() {
        com.assistant.vision.CameraProfile.setCaptureScale(640,288,1600,720)
        // No56-D4: the old repeat(4) expire dance hardcoded COAST_FRAMES=3
        // plus one with no reset API; GoalkeeperDetector.reset() in setUp
        // now clears the coasting state explicitly, so this test pins only
        // its own claim: a 100000-pixel green blob exceeds 2% of the
        // 640x288 capture (3686px) and is skipped, so no keeper is reported.
        assertFalse(GoalkeeperDetector.detect(listOf(ConnectedComponentEngine.Blob(0,0,639,287,100000,0f,220f,0f))).detected)
    }
    @Test fun keeperCoastHoldsThreeFramesThenExpires() {
        // No56-D4: pins the COAST_FRAMES=3 contract directly. A small green
        // blob (50px, well under 2% of the 640x288 capture) is a real keeper
        // detection; the next three empty frames coast it (detected, same
        // position), and the fourth loses it.
        com.assistant.vision.CameraProfile.setCaptureScale(640,288,1600,720)
        val keeper=GoalkeeperDetector.detect(listOf(ConnectedComponentEngine.Blob(100,100,110,120,50,0f,220f,0f)))
        assertTrue(keeper.detected)
        assertEquals(105f,keeper.x,0f)
        assertEquals(110f,keeper.y,0f)
        repeat(3) {
            val coasted=GoalkeeperDetector.detect(emptyList())
            assertTrue(coasted.detected)
            assertEquals(105f,coasted.x,0f)
            assertEquals(110f,coasted.y,0f)
        }
        assertFalse(GoalkeeperDetector.detect(emptyList()).detected)
    }
}
