package com.semavoice.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.semavoice.core.common.AppError
import com.semavoice.core.common.DispatcherProvider
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.PcmAudio
import com.semavoice.core.common.SemaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RecordingConfig(
    val targetSampleRate: Int = PcmAudio.WHISPER_SAMPLE_RATE,
    val maxDurationMs: Long = 120_000,
    val silenceTimeoutMs: Long = 2_500,
    val autoStopOnSilence: Boolean = false,
    val gain: Float = 1f,
    val trimSilence: Boolean = true,
)

/**
 * Microphone capture built on [AudioRecord]. Produces 16 kHz mono float PCM for Whisper,
 * resampling when the hardware refuses 16 kHz. All state changes go through
 * [RecordingStateMachine] so the UI can rely on legal transitions only.
 *
 * Threading: reads happen on an IO coroutine; public methods are safe from any thread.
 * No audio ever leaves this class except through [stop].
 */
class AudioRecorder(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var readerJob: Job? = null
    private var chunks = ArrayList<FloatArray>()
    private var totalSamples = 0
    private var captureRate = 16_000
    private var config = RecordingConfig()
    @Volatile private var paused = false
    @Volatile private var stopRequested = false

    private fun transition(event: RecordingEvent) {
        val next = RecordingStateMachine.next(_state.value, event)
        if (next != null) _state.value = next else SemaLog.d(TAG, "ignored $event in ${_state.value}")
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasMicrophone(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    suspend fun start(cfg: RecordingConfig = RecordingConfig()): Outcome<Unit> = mutex.withLock {
        if (_state.value.isActive) return Outcome.Failure(AppError.RecordingFailed(IllegalStateException("already recording")))
        if (!hasPermission()) return fail(AppError.MicrophonePermissionDenied())
        if (!hasMicrophone()) return fail(AppError.NoMicrophone())
        transition(RecordingEvent.Start)
        config = cfg
        chunks = ArrayList()
        totalSamples = 0
        paused = false
        stopRequested = false
        naturalResult = null

        val record = try {
            openRecord(cfg.targetSampleRate) ?: openRecord(48_000) ?: openRecord(44_100)
        } catch (se: SecurityException) {
            return fail(AppError.MicrophonePermissionDenied())
        } ?: return fail(AppError.RecordingFailed(IllegalStateException("AudioRecord init failed")))

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            return fail(AppError.MicrophoneBusy(e))
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return fail(AppError.MicrophoneBusy())
        }
        audioRecord = record
        transition(RecordingEvent.Started)
        readerJob = scope.launch { readLoop(record) }
        Outcome.Success(Unit)
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(rate: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, rate), // >= 0.5 s buffer to tolerate scheduling hiccups
        )
        return if (record.state == AudioRecord.STATE_INITIALIZED) { captureRate = rate; record } else { record.release(); null }
    }

    private suspend fun readLoop(record: AudioRecord) {
        val vad = VoiceActivityDetector(captureRate)
        val frame = ShortArray(vad.frameSize)
        var speechSeen = false
        var lastSpeechAtMs = 0L
        var zeroReads = 0
        val start = System.nanoTime()
        var elapsedMs = 0L
        var pausedOffsetNs = 0L
        var pauseStartNs = 0L
        // Use the running coroutine's own context: `readerJob` may not be assigned yet when this starts.
        while (currentCoroutineContext().isActive && !stopRequested) {
            if (paused) {
                if (pauseStartNs == 0L) pauseStartNs = System.nanoTime()
                kotlinx.coroutines.delay(20)
                continue
            } else if (pauseStartNs != 0L) {
                pausedOffsetNs += System.nanoTime() - pauseStartNs
                pauseStartNs = 0L
            }
            val n = record.read(frame, 0, frame.size)
            if (n < 0) { transition(RecordingEvent.Fail(AppError.RecordingInterrupted())); break }
            if (n == 0) { if (++zeroReads > 50) { transition(RecordingEvent.Fail(AppError.MicrophoneBusy())); break }; continue }
            zeroReads = 0
            val floats = PcmConversions.shortsToFloats(frame, n, config.gain)
            chunks.add(floats)
            totalSamples += n
            val f = vad.analyze(floats)
            elapsedMs = (System.nanoTime() - start - pausedOffsetNs) / 1_000_000
            if (f.isSpeech) { speechSeen = true; lastSpeechAtMs = elapsedMs }
            transition(RecordingEvent.Tick(elapsedMs, vad.levelOf(f.db), f.isSpeech))
            if (elapsedMs >= config.maxDurationMs) { SemaLog.i(TAG, "max duration reached"); break }
            if (config.autoStopOnSilence && speechSeen && elapsedMs - lastSpeechAtMs >= config.silenceTimeoutMs) {
                SemaLog.i(TAG, "silence timeout reached"); break
            }
        }
        if (!stopRequested && (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused)) {
            // Natural end (max duration / silence). Finish like an explicit stop.
            naturalResult = finishInternal()
        }
    }

    @Volatile private var naturalResult: Outcome<PcmAudio>? = null

    fun pause() { if (_state.value is RecordingState.Recording) { paused = true; transition(RecordingEvent.Pause) } }
    fun resume() { if (_state.value is RecordingState.Paused) { paused = false; transition(RecordingEvent.Resume) } }

    /** Stops capture and returns the recorded audio resampled to the target rate. */
    suspend fun stop(): Outcome<PcmAudio> {
        val current = _state.value
        if (!(current is RecordingState.Recording || current is RecordingState.Paused || current is RecordingState.Starting)) {
            return Outcome.Failure(AppError.RecordingFailed(IllegalStateException("not recording")))
        }
        stopRequested = true
        paused = false
        readerJob?.join()
        naturalResult?.let { return it }
        return finishInternal()
    }

    /** Result of a recording that ended on its own (max duration or silence timeout). */
    fun takeNaturalResult(): Outcome<PcmAudio>? = naturalResult.also { naturalResult = null }

    private suspend fun finishInternal(): Outcome<PcmAudio> = mutex.withLock {
        transition(RecordingEvent.Stop)
        releaseRecord()
        val raw = concat()
        val resampled = withContext(dispatchers.default) { Resampler.resample(raw, captureRate, config.targetSampleRate) }
        val samples = if (config.trimSilence) SilenceTrimmer.trim(resampled, config.targetSampleRate) else resampled
        val durationMs = samples.size * 1000L / config.targetSampleRate
        transition(RecordingEvent.Stopped(durationMs, samples.size))
        chunks = ArrayList()
        return when (val s = _state.value) {
            is RecordingState.Finished -> Outcome.Success(PcmAudio(samples, config.targetSampleRate))
            is RecordingState.Failed -> Outcome.Failure(s.error)
            else -> Outcome.Failure(AppError.RecordingFailed())
        }
    }

    suspend fun cancel() {
        stopRequested = true
        paused = false
        readerJob?.cancelAndJoin()
        mutex.withLock {
            releaseRecord()
            chunks = ArrayList()
            transition(RecordingEvent.Cancel)
        }
    }

    fun reset() { transition(RecordingEvent.Reset) }

    /** Releases the microphone. Safe to call repeatedly; used from IME onDestroy. */
    fun release() {
        stopRequested = true
        readerJob?.cancel()
        releaseRecord()
        if (_state.value.isActive) _state.value = RecordingState.Cancelled
    }

    private fun releaseRecord() {
        audioRecord?.let {
            try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        audioRecord = null
    }

    private fun concat(): FloatArray {
        val out = FloatArray(totalSamples)
        var pos = 0
        for (c in chunks) { System.arraycopy(c, 0, out, pos, c.size); pos += c.size }
        return out
    }

    private fun fail(e: AppError): Outcome<Unit> {
        transition(RecordingEvent.Fail(e))
        return Outcome.Failure(e)
    }

    private companion object { const val TAG = "AudioRecorder" }
}
