package com.assistant.adapter.smartassist

object HighPerformanceRuntimeLogger {

    private const val BUFFER_SIZE = 512

    private val logBuffer =
        LongArray(BUFFER_SIZE)

    private var writeIndex = 0

    fun logEngineState(
        moduleId:Int,
        executionStatusCode:Int,
        metricPayload:Int
    ) {

        val packedLog =
            (moduleId.toLong() shl 48) or
            ((executionStatusCode.toLong() and 0xFFFFL) shl 32) or
            (metricPayload.toLong() and 0xFFFFFFFFL)

        logBuffer[writeIndex] =
            packedLog

        writeIndex =
            (writeIndex + 1) %
            BUFFER_SIZE
    }

    fun dumpLogsToCrashReport(): List<String> {

        val report =
            mutableListOf<String>()

        logBuffer.forEach { packed ->

            if (packed != 0L) {

                val mod =
                    (packed shr 48) and 0xFFFFL

                val status =
                    (packed shr 32) and 0xFFFFL

                val metric =
                    packed and 0xFFFFFFFFL

                report.add(
                    "MOD:$mod | STAT:$status | METRIC:$metric"
                )
            }
        }

        return report
    }
}
