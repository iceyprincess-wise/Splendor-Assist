package com.assistant.diagnostic
object Phase7FrameContext {
    @Volatile var rowStride = 0; @Volatile var pixelStride = 0
    @Volatile var isDirect = false; @Volatile var hasArray = false
    @Volatile var capacity = 0; @Volatile var limit = 0
    @Volatile var scanTimeNs = 0L; @Volatile var cceTimeNs = 0L
    @Volatile var visRemTimeNs = 0L; @Volatile var asmTimeNs = 0L
    @Volatile var decTimeNs = 0L; @Volatile var sampleCount = 0
    @Volatile var blobCount = 0
    fun reset() { scanTimeNs=0; cceTimeNs=0; visRemTimeNs=0; asmTimeNs=0; decTimeNs=0; sampleCount=0; blobCount=0 }
}
