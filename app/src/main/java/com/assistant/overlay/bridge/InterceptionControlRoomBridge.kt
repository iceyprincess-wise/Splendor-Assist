package com.assistant.overlay.bridge

import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.overlay.repository.InterceptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InterceptionControlRoomBridge(
    private val repository: InterceptionRepository
) {

    private val scope =
        CoroutineScope(
            SupervisorJob() +
            Dispatchers.Default
        )

    fun start() {

        scope.launch {

            repository.state.collectLatest { state ->

                InterceptionRuntimeRegistry.enabled =
                    state.enabled

                InterceptionRuntimeRegistry.autoIntercept =
                    state.autoIntercept

                InterceptionRuntimeRegistry.awareness =
                    state.awareness

                InterceptionRuntimeRegistry.prediction =
                    state.prediction
            }
        }
    }
}
