package com.assistant.execution

import com.assistant.diagnostic.RuntimeLogger

object HybridExecutionTerminal {

    fun priority(
        source: ExecutionSource
    ): Int {

        return when(source) {
            ExecutionSource.SMART_ASSIST -> 100
            ExecutionSource.GOALKEEPER -> 90
            ExecutionSource.INTERCEPTION -> 80
            ExecutionSource.STUTTER -> 70
            ExecutionSource.FUTURE_ENGINE -> 50
        }
    }

    fun route(
        request: ExecutionRequest
    ): Boolean {

        RuntimeLogger.execution("HYBRID_ROUTE","source=${request.source} phase=${request.phase}")

        return CentralExecutionBus.submit(request)
    }
}
