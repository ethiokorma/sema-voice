package com.semavoice.core.audio

import com.semavoice.core.common.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateMachineTest {
    private fun run(vararg events: RecordingEvent): RecordingState {
        var s: RecordingState = RecordingState.Idle
        for (e in events) s = RecordingStateMachine.next(s, e) ?: s
        return s
    }

    @Test fun happyPath() {
        val s = run(
            RecordingEvent.Start, RecordingEvent.Started,
            RecordingEvent.Tick(100, 0.5f, true),
            RecordingEvent.Stop, RecordingEvent.Stopped(1200, 19200),
        )
        assertEquals(RecordingState.Finished(1200, 19200), s)
    }

    @Test fun pauseAndResumeKeepElapsed() {
        val s = run(RecordingEvent.Start, RecordingEvent.Started, RecordingEvent.Tick(900, 0.1f, false), RecordingEvent.Pause)
        assertEquals(RecordingState.Paused(900), s)
        val r = RecordingStateMachine.next(s, RecordingEvent.Resume)
        assertTrue(r is RecordingState.Recording && r.elapsedMs == 900L)
    }

    @Test fun emptyRecordingBecomesFailure() {
        val s = run(RecordingEvent.Start, RecordingEvent.Started, RecordingEvent.Stop, RecordingEvent.Stopped(0, 0))
        assertTrue(s is RecordingState.Failed && (s as RecordingState.Failed).error is AppError.EmptyRecording)
    }

    @Test fun cancelFromAnyActiveState() {
        assertEquals(RecordingState.Cancelled, run(RecordingEvent.Start, RecordingEvent.Cancel))
        assertEquals(RecordingState.Cancelled, run(RecordingEvent.Start, RecordingEvent.Started, RecordingEvent.Cancel))
        assertEquals(RecordingState.Cancelled, run(RecordingEvent.Start, RecordingEvent.Started, RecordingEvent.Pause, RecordingEvent.Cancel))
    }

    @Test fun illegalTransitionsAreIgnored() {
        assertNull(RecordingStateMachine.next(RecordingState.Idle, RecordingEvent.Stop))
        assertNull(RecordingStateMachine.next(RecordingState.Idle, RecordingEvent.Pause))
        assertNull(RecordingStateMachine.next(RecordingState.Idle, RecordingEvent.Tick(1, 0f, false)))
        assertNull(RecordingStateMachine.next(RecordingState.Cancelled, RecordingEvent.Cancel))
    }

    @Test fun resetOnlyWhenInactive() {
        assertNull(RecordingStateMachine.next(RecordingState.Recording(1, 0f, false), RecordingEvent.Reset))
        assertEquals(RecordingState.Idle, RecordingStateMachine.next(RecordingState.Cancelled, RecordingEvent.Reset))
        assertEquals(RecordingState.Idle, RecordingStateMachine.next(RecordingState.Finished(1, 1), RecordingEvent.Reset))
    }

    @Test fun failureIsRecoverableByStart() {
        val failed = run(RecordingEvent.Start, RecordingEvent.Fail(AppError.MicrophoneBusy()))
        assertTrue(failed is RecordingState.Failed)
        assertEquals(RecordingState.Starting, RecordingStateMachine.next(failed, RecordingEvent.Start))
    }
}
