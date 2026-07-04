package com.assistant.overlay.dvr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DvrRuntimeCoordinator {

    private val _state =
        MutableStateFlow(DvrRuntimeState.OFF)

    val state: StateFlow<DvrRuntimeState> =
        _state.asStateFlow()

    fun arm() {
        _state.value = DvrRuntimeState.ARMED
    }

    fun startRecording() {
        if (_state.value == DvrRuntimeState.ARMED)
            _state.value = DvrRuntimeState.RECORDING
    }

    fun saving() {
        _state.value = DvrRuntimeState.SAVING
    }

    fun stop() {
        _state.value = DvrRuntimeState.OFF
    }

    fun recording() =
        _state.value == DvrRuntimeState.RECORDING

    fun armed() =
        _state.value == DvrRuntimeState.ARMED
}
