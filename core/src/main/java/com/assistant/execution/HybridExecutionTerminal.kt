package com.assistant.execution

import com.assistant.diagnostic.RuntimeLogger

object HybridExecutionTerminal {

    fun priority(
        source: ExecutionSource
    ): Int {

        return when(source) {
            ExecutionSource.GOALKEEPER -> 100
            ExecutionSource.INTERCEPTION -> 100
            ExecutionSource.SMART_ASSIST -> 99
            ExecutionSource.STUTTER -> 98
        }
    }

    fun route(
        request: ExecutionRequest
    ): Boolean {

        RuntimeLogger.execution("HYBRID_ROUTE","source=${request.source} phase=${request.phase}")

        return CentralExecutionBus.submit(request)
    }
}
