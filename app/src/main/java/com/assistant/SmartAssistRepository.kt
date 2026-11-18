package com.assistant









import android.content.Context

import android.content.SharedPreferences


























import com.assistant.runtime.*


























import kotlin.math.*





















import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow




class SmartAssistRepository(context: Context) {
    companion object {
        private const val PREFS_NAME = "smart_assist_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PASS = "pass_threshold"
        private const val KEY_SHOT = "shot_threshold"
        private const val KEY_CROSS = "cross_threshold"
        private const val KEY_PANIC_THRESHOLD = "panic_threshold"
        private const val KEY_AUTHORITY = "authority"
        
        // Static accessor for controllers that need it
        private var instance: SmartAssistRepository? = null
        private var staticState: SmartAssistState = SmartAssistState()
        
        fun enabled(): Boolean = staticState.enabled
        fun panicActive(): Boolean = staticState.panicMode
        fun configuration(): SmartAssistConfiguration = staticState.configuration
        
        // PHASE10_PANIC_PERSISTENCE_FINAL_MARKER
        fun activatePanic() {
            instance?.updatePanicMode(true)
                ?: run {
                    staticState = staticState.copy(panicMode = true)
                }
        }
        
        fun clearPanic() {
            instance?.updatePanicMode(false)
                ?: run {
                    staticState = staticState.copy(panicMode = false)
                }
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
            panicMode = false, // Never restore stale panic state across sessions
            configuration = SmartAssistConfiguration(
                // No66-D17: persisted ints were read verbatim — a stale or
                // out-of-range value flowed straight into the config and
                // crashed the control room (Slider throws outside 0..100).
                // Same class as No63-D12/D13 (INT/GK repos); clamp here.
                passThreshold = prefs.getInt(KEY_PASS, 50).coerceIn(0, 100),
                shotThreshold = prefs.getInt(KEY_SHOT, 50).coerceIn(0, 100),
                crossThreshold = prefs.getInt(KEY_CROSS, 50).coerceIn(0, 100),
                panicThreshold = prefs.getInt(KEY_PANIC_THRESHOLD, 80).coerceIn(0, 100),
                authority = prefs.getInt(KEY_AUTHORITY,100).coerceIn(0, 100)
            )
        )
    }
    
    fun saveState(newState: SmartAssistState) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, newState.enabled)
            // putBoolean(KEY_PANIC, newState.panicMode) // Panic is transient, do not persist
            putInt(KEY_PASS, newState.configuration.passThreshold)
            putInt(KEY_SHOT, newState.configuration.shotThreshold)
            putInt(KEY_CROSS, newState.configuration.crossThreshold)
            putInt(KEY_PANIC_THRESHOLD, newState.configuration.panicThreshold)
            putInt(KEY_AUTHORITY, newState.configuration.authority)
            apply()
        }
        _state.value = newState.copy(lastUpdated = System.currentTimeMillis())
        updateStaticState()
    }
    
    fun updateEnabled(enabled: Boolean) = saveState(_state.value.copy(enabled = enabled))
    fun updatePanicMode(panic: Boolean) = saveState(_state.value.copy(panicMode = panic))
    // No66-D17: no clamp — any caller could persist an out-of-range threshold
    // that later crashes the control-room sliders. The repo is the choke
    // point (all writers go through here, incl. ModMenu); clamp here.
    fun updateThresholds(pass: Int, shot: Int, cross: Int) = 
        saveState(_state.value.copy(configuration = _state.value.configuration.copy(
            passThreshold = pass.coerceIn(0, 100), shotThreshold = shot.coerceIn(0, 100), crossThreshold = cross.coerceIn(0, 100)
        )))

    fun updateAuthority(authority:Int)=
        saveState(
            _state.value.copy(
                configuration =
                    _state.value.configuration.copy(
                        authority = authority.coerceIn(0, 100)
                    )
            )
        )

fun getCurrentState(): SmartAssistState = _state.value
}

/* ========
SpaceOccupancyEngine
======== */
