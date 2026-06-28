package com.phantom.dashcam

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an exported (merged) clip should go. */
enum class DashcamExportDestination {
    DOWNLOAD_TO_GALLERY,
    SHARE_OUT;

    companion object {
        fun fromName(name: String?): DashcamExportDestination {
            return entries.firstOrNull { it.name == name } ?: SHARE_OUT
        }
    }
}

/**
 * Shared, process-wide state of the current export/merge job so the progress dialog can observe it
 * while visible, and so the work keeps running (and reporting to the notification) in the
 * background after the dialog is dismissed.
 */
object DashcamExportProgress {

    sealed interface State {
        object Idle : State
        data class Running(val fraction: Float, val etaSeconds: Long?) : State
        data class Done(val destination: DashcamExportDestination, val shareFilePath: String?) : State
        object Failed : State
        object Empty : State
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
