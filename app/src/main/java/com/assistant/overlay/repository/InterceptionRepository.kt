package com.assistant.overlay.repository

import android.content.Context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InterceptionState(
    val enabled: Boolean = true,
    val autoIntercept: Boolean = true,
    val awareness: Int = 100,
    val prediction: Int = 100,
    // No65-D26: armed joins the persisted state — it used to live only in
    // InterceptionRuntimeRegistry, so the ModMenu switch wrote the registry
    // and a restart silently reset it to true.
    val armed: Boolean = true
)

class InterceptionRepository(context: Context) {
    private val prefs = context.getSharedPreferences("interception_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<InterceptionState> = _state.asStateFlow()
    
    private fun loadState(): InterceptionState {
        return InterceptionState(
            enabled = prefs.getBoolean("enabled", true),
            autoIntercept = prefs.getBoolean("auto_intercept", true),
            // No63-D13: persisted ints were read verbatim — a stale or
            // out-of-range value in prefs (written by an older build, or
            // by a hand-edited store) flowed straight into the flow and,
            // via the bridge, into NativePorts.threat as aw/10 and pr/10
            // (a negative would subtract priority; >100 would inflate
            // it). Clamp at the read site.
            awareness = clampSlider(prefs.getInt("awareness", 100)),
            prediction = clampSlider(prefs.getInt("prediction", 100)),
            armed = prefs.getBoolean("armed", true)
        )
    }
    
    private fun save(state: InterceptionState) {
        prefs.edit().apply {
            putBoolean("enabled", state.enabled)
            putBoolean("auto_intercept", state.autoIntercept)
            putInt("awareness", state.awareness)
            putInt("prediction", state.prediction)
            putBoolean("armed", state.armed)
            apply()
        }
        _state.value = state
    }
    
    fun updateEnabled(enabled: Boolean) = save(_state.value.copy(enabled = enabled))
    fun updateAutoIntercept(auto: Boolean) = save(_state.value.copy(autoIntercept = auto))
    // No65-D26: the armed switch finally persists — see InterceptionState.
    fun updateArmed(armed: Boolean) = save(_state.value.copy(armed = armed))
    // No63-D12: updateAwareness/updatePrediction accepted any Int with
    // no clamp — the values flow straight through the bridge into
    // NativePorts.threat as aw/10 and pr/10 (negative would subtract
    // priority; >100 would inflate it). The ModMenu sliders clamp at the
    // UI only; the repo is the choke point, so clamp here.
    fun updateAwareness(awareness: Int) = save(_state.value.copy(awareness = clampSlider(awareness)))
    fun updatePrediction(prediction: Int) = save(_state.value.copy(prediction = clampSlider(prediction)))

    companion object {
        @Volatile private var instance: InterceptionRepository? = null

        fun shared(context: Context): InterceptionRepository =
            instance ?: synchronized(this) {
                instance ?: InterceptionRepository(context.applicationContext).also { instance = it }
            }

        // No63-D12/D13: the clamp the update methods and loadState share.
        // Internal (not private) so the harness can pin it — the repo
        // itself needs a Context, so the clamp is the testable unit.
        internal fun clampSlider(value: Int): Int = value.coerceIn(0, 100)
    }
}
