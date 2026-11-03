package com.assistant

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ForegroundSegmentationTest {
    @Test fun saturatedRedAndBlueJerseysReachThePlayerDetector() {
        val width=8;val height=8;val row=width*4+8;val b=ByteBuffer.allocate(row*height)
        for(y in 0 until height) for(x in 0 until width) {
            val i=y*row+x*4
            if(x<4) b.put(i,255.toByte()) else b.put(i+2,255.toByte())
        }
        val f=FrameNormalizer.NormalizedFrame(b,width,height,row,4,FrameNormalizer.FrameMetadata(1,0))
        val blobs=ConnectedComponentEngine.extract(FrameScanner.scan(f))
        assertEquals(2,blobs.size)
        assertEquals(2,PlayerDetector.detect(blobs).playerCount)
    }
    @Test fun colorBoundariesDoNotMergeBallAndJerseyIntoOneBlob() {
        val b=ByteBuffer.allocate(8*4)
        for(x in 0 until 8) {
            b.put(x*4,255.toByte())
            if(x>=4) { b.put(x*4+1,255.toByte());b.put(x*4+2,255.toByte()) }
        }
        val f=FrameNormalizer.NormalizedFrame(b,8,1,32,4,FrameNormalizer.FrameMetadata(1,0))
        assertEquals(2,ConnectedComponentEngine.extract(FrameScanner.scan(f)).size)
    }
    @Test fun pitchSizedGreenBlobIsNotKeeperEvidence() {
        com.assistant.vision.CameraProfile.setCaptureScale(640,288,1600,720)
        // Expire any prior coasting state before checking this negative observation.
        repeat(4) { GoalkeeperDetector.detect(emptyList()) }
        assertFalse(GoalkeeperDetector.detect(listOf(ConnectedComponentEngine.Blob(0,0,639,287,100000,0f,220f,0f))).detected)
    }
}
