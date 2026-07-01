package com.assistant.adapter.smartassist

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SmartAssistConfiguration(
    val passThreshold: Int = 50,
    val shotThreshold: Int = 50,
    val crossThreshold: Int = 50,
    val panicThreshold: Int = 80
)

data class SmartAssistState(
    val enabled: Boolean = true,
    val panicMode: Boolean = false,
    val configuration: SmartAssistConfiguration = SmartAssistConfiguration(),
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
        private const val KEY_PANIC_THRESHOLD = "panic_threshold"
        
        // Static accessor for controllers that need it
        private var instance: SmartAssistRepository? = null
        private var staticState: SmartAssistState = SmartAssistState()
        
        fun enabled(): Boolean = staticState.enabled
        fun panicActive(): Boolean = staticState.panicMode
        fun configuration(): SmartAssistConfiguration = staticState.configuration
        
        fun activatePanic() {
            staticState = staticState.copy(panicMode = true)
        }
        
        fun clearPanic() {
            staticState = staticState.copy(panicMode = false)
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SmartAssistState> = _state.asStateFlow()
    
    init {
        instance = this
        updateStaticState()
    }
    
    private fun updateStaticState() {
        staticState = _state.value
    }
    
    private fun loadState(): SmartAssistState {
        return SmartAssistState(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            panicMode = prefs.getBoolean(KEY_PANIC, false),
            configuration = SmartAssistConfiguration(
                passThreshold = prefs.getInt(KEY_PASS, 50),
                shotThreshold = prefs.getInt(KEY_SHOT, 50),
                crossThreshold = prefs.getInt(KEY_CROSS, 50),
                panicThreshold = prefs.getInt(KEY_PANIC_THRESHOLD, 80)
            )
        )
    }
    
    fun saveState(newState: SmartAssistState) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, newState.enabled)
            putBoolean(KEY_PANIC, newState.panicMode)
            putInt(KEY_PASS, newState.configuration.passThreshold)
            putInt(KEY_SHOT, newState.configuration.shotThreshold)
            putInt(KEY_CROSS, newState.configuration.crossThreshold)
            putInt(KEY_PANIC_THRESHOLD, newState.configuration.panicThreshold)
            apply()
        }
        _state.value = newState.copy(lastUpdated = System.currentTimeMillis())
        updateStaticState()
    }
    
    fun updateEnabled(enabled: Boolean) = saveState(_state.value.copy(enabled = enabled))
    fun updatePanicMode(panic: Boolean) = saveState(_state.value.copy(panicMode = panic))
    fun updateThresholds(pass: Int, shot: Int, cross: Int) = 
        saveState(_state.value.copy(configuration = _state.value.configuration.copy(
            passThreshold = pass, shotThreshold = shot, crossThreshold = cross
        )))
    
    fun getCurrentState(): SmartAssistState = _state.value
}
