package com.semavoice.core.audio

import com.semavoice.core.common.AppError

/**
 * Pure state machine for the recording lifecycle. It holds no Android dependencies so the
 * transitions can be unit tested exhaustively. [AudioRecorder] drives it; UI observes it.
 */
sealed class RecordingState {
    data object Idle : RecordingState()
    data object Starting : RecordingState()
    data class Recording(val elapsedMs: Long, val level: Float, val speechDetected: Boolean) : RecordingState()
    data class Paused(val elapsedMs: Long) : RecordingState()
    data object Stopping : RecordingState()
    data class Finished(val durationMs: Long, val sampleCount: Int) : RecordingState()
    data class Failed(val error: AppError) : RecordingState()
    data object Cancelled : RecordingState()

    val isActive: Boolean get() = this is Starting || this is Recording || this is Paused || this is Stopping
}

sealed class RecordingEvent {
    data object Start : RecordingEvent()
    data object Started : RecordingEvent()
    data class Tick(val elapsedMs: Long, val level: Float, val speechDetected: Boolean) : RecordingEvent()
    data object Pause : RecordingEvent()
    data object Resume : RecordingEvent()
    data object Stop : RecordingEvent()
    data class Stopped(val durationMs: Long, val sampleCount: Int) : RecordingEvent()
    data object Cancel : RecordingEvent()
    data class Fail(val error: AppError) : RecordingEvent()
    data object Reset : RecordingEvent()
}

object RecordingStateMachine {
    /**
     * Returns the next state, or null when the event is not legal in the current state
     * (callers treat null as "ignore"). Illegal transitions never crash the keyboard.
     */
    fun next(state: RecordingState, event: RecordingEvent): RecordingState? = when (event) {
        RecordingEvent.Start -> when (state) {
            RecordingState.Idle, is RecordingState.Finished, is RecordingState.Failed, RecordingState.Cancelled -> RecordingState.Starting
            else -> null
        }
        RecordingEvent.Started -> if (state is RecordingState.Starting) RecordingState.Recording(0, 0f, false) else null
        is RecordingEvent.Tick -> if (state is RecordingState.Recording) {
            RecordingState.Recording(event.elapsedMs, event.level, event.speechDetected)
        } else null
        RecordingEvent.Pause -> if (state is RecordingState.Recording) RecordingState.Paused(state.elapsedMs) else null
        RecordingEvent.Resume -> if (state is RecordingState.Paused) RecordingState.Recording(state.elapsedMs, 0f, false) else null
        RecordingEvent.Stop -> when (state) {
            is RecordingState.Recording, is RecordingState.Paused, RecordingState.Starting -> RecordingState.Stopping
            else -> null
        }
        is RecordingEvent.Stopped -> if (state is RecordingState.Stopping) {
            if (event.sampleCount == 0) RecordingState.Failed(AppError.EmptyRecording())
            else RecordingState.Finished(event.durationMs, event.sampleCount)
        } else null
        RecordingEvent.Cancel -> if (state.isActive) RecordingState.Cancelled else null
        is RecordingEvent.Fail -> if (state.isActive || state is RecordingState.Idle) RecordingState.Failed(event.error) else null
        RecordingEvent.Reset -> if (!state.isActive) RecordingState.Idle else null
    }
}
