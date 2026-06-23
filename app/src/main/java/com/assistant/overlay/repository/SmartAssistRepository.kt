package com.assistant.overlay.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SmartAssistState(
    val enabled: Boolean = false,
    val panicMode: Boolean = false,
    val passThreshold: Int = 50,
    val shotThreshold: Int = 50,
    val crossThreshold: Int = 50,
    val lastUpdated: Long = System.currentTimeMillis()
)

class SmartAssistRepository(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "smart_assist_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PANIC = "panic_mode"
        private const val KEY_PASS = "pass_threshold"
        private const val KEY_SHOT = "shot_threshold"
        private const val KEY_CROSS = "cross_threshold"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SmartAssistState> = _state.asStateFlow()
    
    private fun loadState(): SmartAssistState {
        return SmartAssistState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            panicMode = prefs.getBoolean(KEY_PANIC, false),
            passThreshold = prefs.getInt(KEY_PASS, 50),
            shotThreshold = prefs.getInt(KEY_SHOT, 50),
            crossThreshold = prefs.getInt(KEY_CROSS, 50)
        )
    }
    
    fun saveState(newState: SmartAssistState) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, newState.enabled)
            putBoolean(KEY_PANIC, newState.panicMode)
            putInt(KEY_PASS, newState.passThreshold)
            putInt(KEY_SHOT, newState.shotThreshold)
            putInt(KEY_CROSS, newState.crossThreshold)
            apply()
        }
        _state.value = newState.copy(lastUpdated = System.currentTimeMillis())
    }
    
    fun updateEnabled(enabled: Boolean) {
        saveState(_state.value.copy(enabled = enabled))
    }
    
    fun updatePanicMode(panic: Boolean) {
        saveState(_state.value.copy(panicMode = panic))
    }
    
    fun updateThresholds(pass: Int, shot: Int, cross: Int) {
        saveState(_state.value.copy(
            passThreshold = pass,
            shotThreshold = shot,
            crossThreshold = cross
        ))
    }
    
    fun getCurrentState(): SmartAssistState = _state.value
}
