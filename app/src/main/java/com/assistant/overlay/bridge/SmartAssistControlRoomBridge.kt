package com.assistant.overlay.bridge

import kotlinx.coroutines.flow.StateFlow
import com.assistant.SmartAssistRepository
import com.assistant.SmartAssistState


class SmartAssistControlRoomBridge(
    private val repository: SmartAssistRepository
) {
    
    val state: StateFlow<SmartAssistState> = repository.state
    
    fun updateEnabled(enabled: Boolean) {
        repository.updateEnabled(enabled)
    }
    
    fun updatePanicMode(panic: Boolean) {
        repository.updatePanicMode(panic)
    }
    
    fun updateThresholds(pass: Int, shot: Int, cross: Int) {
        repository.updateThresholds(pass, shot, cross)
    }

    // No66-D34: the authority path (twin parity) — the activity's authority
    // slider used to call repo + coordinator directly; the bridge owns the
    // pair-write now so the bridge is the single write path for the room.
    fun updateAuthority(authority: Int) {
        repository.updateAuthority(authority)
        com.assistant.RuntimePerformanceCoordinator.updateAuthority(authority)
    }
    
    fun saveCurrentSettings(): Boolean {
        return try {
            val current = repository.getCurrentState()
            repository.saveState(current)
            true
        } catch (e: Exception) {
            // No66-D18: was a silent `false` — a failed save looked exactly
            // like success to the caller. Log it like the twin does.
            com.assistant.diagnostic.RuntimeLogger.log(
                "saveCurrentSettings failed: " + e.message, "SMART_ASSIST")
            false
        }
    }
    
    fun getCurrentSettings(): SmartAssistState {
        return repository.getCurrentState()
    }
}
