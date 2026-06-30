package com.phantom.dashcam

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state of the current stabilize job, mirroring [DashcamExportProgress], so the dialog
 * can observe live progress while visible and the work keeps reporting to its notification after the
 * dialog is dismissed.
 */
object DashcamStabilizeProgress {

    sealed interface State {
        object Idle : State
        data class Running(val fraction: Float, val etaSeconds: Long?) : State
        object Done : State
        object Failed : State
        object Cancelled : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun set(value: State) {
        _state.value = value
    }

    fun reset() {
        _state.value = State.Idle
    }
}
