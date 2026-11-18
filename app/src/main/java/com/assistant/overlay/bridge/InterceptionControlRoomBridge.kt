package com.assistant.overlay.bridge

import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.overlay.repository.InterceptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InterceptionControlRoomBridge(
    private val repository: InterceptionRepository
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    // No66-D19: start() is idempotent, and stop() cancels the scope.
    // Before this the scope was never cancelled: OverlayService.onDestroy()
    // never touched the bridge, so a stop/start cycle in one process
    // (START_STICKY) left the old collector collecting the shared repo flow
    // and writing the registry forever. OverlayService.onDestroy() calls
    // stop() (added in the same patch, next to `modMenuController = null`).
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.state.collectLatest { state ->
                InterceptionRuntimeRegistry.enabled = state.enabled
                InterceptionRuntimeRegistry.autoIntercept = state.autoIntercept
                InterceptionRuntimeRegistry.awareness = state.awareness
                InterceptionRuntimeRegistry.prediction = state.prediction
                // No65-D26: armed is restored from the persisted state now.
                InterceptionRuntimeRegistry.armed = state.armed
            }
        }
    }

    // No66-D19: cancels the collector. Called from OverlayService.onDestroy().
    // Terminal for this bridge instance: a cancelled scope cannot launch
    // again, so a restarted service builds a fresh bridge (as today).
    fun stop() {
        scope.cancel()
    }
}
