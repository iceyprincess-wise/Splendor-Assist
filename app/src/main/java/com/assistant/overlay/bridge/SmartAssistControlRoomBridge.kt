package com.assistant.overlay.bridge

import kotlinx.coroutines.flow.StateFlow
import com.assistant.adapter.smartassist.SmartAssistRepository
import com.assistant.adapter.smartassist.SmartAssistState


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
    
    fun saveCurrentSettings(): Boolean {
        return try {
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun getCurrentSettings(): SmartAssistState {
        return repository.getCurrentState()
    }
}
