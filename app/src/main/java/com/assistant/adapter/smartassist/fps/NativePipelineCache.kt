package com.assistant.adapter.smartassist.fps

object NativePipelineCache {

    private val vectorBufferX = FloatArray(64)
    private val vectorBufferY = FloatArray(64)

    private var activeNodeCount = 0

    fun cacheNode(index:Int,x:Float,y:Float) {
        if(index in 0..63) {
            vectorBufferX[index]=x
            vectorBufferY[index]=y

            if(index >= activeNodeCount) {
                activeNodeCount=index+1
            }
        }
    }

    fun computeDirectInterpolation(
        startIndex:Int,
        endIndex:Int,
        bias:Float
    ):Long {
        // FIX: guard both indices — unchecked access crashes on any out-of-range call
        if (startIndex !in 0..63 || endIndex !in 0..63) return 0L

        val dx=
            vectorBufferX[endIndex]-
            vectorBufferX[startIndex]

        val dy=
            vectorBufferY[endIndex]-
            vectorBufferY[startIndex]

        val packedX=
            (
                vectorBufferX[startIndex]+
                dx*bias
            ).toBits().toLong()

        val packedY=
            (
                vectorBufferY[startIndex]+
                dy*bias
            ).toBits().toLong()

        return ((packedX shl 32) or (packedY and 0xffffffffL))
    }
}
