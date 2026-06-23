package com.assistant.overlay.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InterceptionState(
    val enabled: Boolean = false,
    val autoIntercept: Boolean = false,
    val awareness: Int = 50,
    val prediction: Int = 50
)

class InterceptionRepository(context: Context) {
    private val prefs = context.getSharedPreferences("interception_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<InterceptionState> = _state.asStateFlow()
    
    private fun loadState(): InterceptionState {
        return InterceptionState(
            enabled = prefs.getBoolean("enabled", false),
            autoIntercept = prefs.getBoolean("auto_intercept", false),
            awareness = prefs.getInt("awareness", 50),
            prediction = prefs.getInt("prediction", 50)
        )
    }
    
    private fun save(state: InterceptionState) {
        prefs.edit().apply {
            putBoolean("enabled", state.enabled)
            putBoolean("auto_intercept", state.autoIntercept)
            putInt("awareness", state.awareness)
            putInt("prediction", state.prediction)
            apply()
        }
        _state.value = state
    }
    
    fun updateEnabled(enabled: Boolean) = save(_state.value.copy(enabled = enabled))
    fun updateAutoIntercept(auto: Boolean) = save(_state.value.copy(autoIntercept = auto))
    fun updateAwareness(awareness: Int) = save(_state.value.copy(awareness = awareness))
    fun updatePrediction(prediction: Int) = save(_state.value.copy(prediction = prediction))
}
