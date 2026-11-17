package com.assistant.overlay.repository

import android.content.Context

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
            // No63-D17: persisted ints were read verbatim — a stale or
            // out-of-range value in prefs flowed straight into the flow
            // and, via sync(), into the runtime state. Clamp at the read
            // site.
            positioning = clampSlider(prefs.getInt("positioning", 50)),
            reactions = clampSlider(prefs.getInt("reactions", 50))
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
    // No63-D18: updatePositioning/updateReactions accepted any Int with
    // no clamp — the values flow through sync() into the runtime state
    // and into the keeper math. The control-room sliders clamp at the UI
    // only; the repo is the choke point, so clamp here.
    fun updatePositioning(positioning: Int) = save(_state.value.copy(positioning = clampSlider(positioning)))
    fun updateReactions(reactions: Int) = save(_state.value.copy(reactions = clampSlider(reactions)))

    companion object {
        @Volatile private var instance: GoalkeeperRepository? = null

        // No63-D16: this repo had NO shared() singleton (unlike
        // InterceptionRepository next door) — three live instances
        // (ModMenuController, AdapterControlRoomSync, the control-room
        // activity), each with its own MutableStateFlow. A save in one
        // instance left the others' flows stale, and the next save from
        // a stale instance reverted the other instance's field values
        // (lost update: instance A sets positioning=80, instance B still
        // holds 50, B's next toggle writes 50 back to prefs). One shared
        // instance, mirroring InterceptionRepository.shared().
        fun shared(context: Context): GoalkeeperRepository =
            instance ?: synchronized(this) {
                instance ?: GoalkeeperRepository(context.applicationContext).also { instance = it }
            }

        // No63-D17/D18: the clamp the load/update sites share. Internal
        // (not private) so the harness can pin it — the repo itself
        // needs a Context, so the clamp is the testable unit.
        internal fun clampSlider(value: Int): Int = value.coerceIn(0, 100)
    }
}
