package com.assistant.overlay.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.assistant.overlay.repository.GoalkeeperRuntimeState

data class GoalkeeperState(
    val enabled: Boolean = true,
    val aggressiveMode: Boolean = false,
    val positioning: Int = 50,
    val reactions: Int = 50
)

class GoalkeeperRepository(context: Context) {
    private val prefs = context.getSharedPreferences("goalkeeper_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadState())

    init {
        GoalkeeperRuntimeState.sync(
            _state.value.enabled,
            _state.value.aggressiveMode,
            _state.value.positioning,
            _state.value.reactions
        )
    }
    val state: StateFlow<GoalkeeperState> = _state.asStateFlow()
    
    private fun loadState(): GoalkeeperState {
        return GoalkeeperState(
            enabled = prefs.getBoolean("enabled", true),
            aggressiveMode = prefs.getBoolean("aggressive", false),
            positioning = prefs.getInt("positioning", 50),
            reactions = prefs.getInt("reactions", 50)
        )
    }
    
    private fun save(state: GoalkeeperState) {
        prefs.edit().apply {
            putBoolean("enabled", state.enabled)
            putBoolean("aggressive", state.aggressiveMode)
            putInt("positioning", state.positioning)
            putInt("reactions", state.reactions)
            apply()
        }
        _state.value = state

        GoalkeeperRuntimeState.sync(
            state.enabled,
            state.aggressiveMode,
            state.positioning,
            state.reactions
        )
    }
    
    fun updateEnabled(enabled: Boolean) = save(_state.value.copy(enabled = enabled))
    fun updateAggressiveMode(aggressive: Boolean) = save(_state.value.copy(aggressiveMode = aggressive))
    fun updatePositioning(positioning: Int) = save(_state.value.copy(positioning = positioning))
    fun updateReactions(reactions: Int) = save(_state.value.copy(reactions = reactions))
}
