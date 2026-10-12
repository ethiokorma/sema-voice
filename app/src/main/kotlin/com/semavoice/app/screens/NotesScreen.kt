package com.semavoice.app.screens

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.semavoice.app.R
import com.semavoice.app.Routes
import com.semavoice.app.notes.NoteRecordingService
import com.semavoice.app.ui.BigMicButton
import com.semavoice.app.ui.Brand
import com.semavoice.app.ui.Clipboard
import com.semavoice.app.ui.ErrorBanner
import com.semavoice.app.ui.Format
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.SemaTopBar
import com.semavoice.app.ui.VSpace
import com.semavoice.core.audio.RecordingConfig
import com.semavoice.core.audio.RecordingState
import com.semavoice.core.audio.WavCodec
import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.PcmAudio
import com.semavoice.core.database.NotesRepository
import com.semavoice.core.database.VoiceNote
import com.semavoice.core.runtime.SemaGraph
import com.semavoice.core.settings.AudioRetention
import com.semavoice.core.settings.DictationMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class NotesUi(
    val recording: RecordingState = RecordingState.Idle,
    val phase: Phase = Phase.Idle,
    val pendingAudio: PcmAudio? = null,
    val askKeepAudio: Boolean = false,
    val error: AppError? = null,
    val query: String = "",
) { enum class Phase { Idle, Recording, Transcribing } }

class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val g = SemaGraph
    private val _ui = MutableStateFlow(NotesUi())
    val ui: StateFlow<NotesUi> = _ui.asStateFlow()
    private var service: NoteRecordingService? = null
    private var stateJob: Job? = null
    val notes = _ui.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as NoteRecordingService.LocalBinder).service
            startInternal()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    fun setQuery(q: String) { _ui.update { it.copy(query = q) } }

    fun startRecording() {
        val ctx = getApplication<Application>()
        _ui.update { it.copy(error = null, phase = NotesUi.Phase.Recording) }
        ctx.startForegroundService(NoteRecordingService.startIntent(ctx))
        ctx.bindService(NoteRecordingService.startIntent(ctx), connection, Context.BIND_AUTO_CREATE)
    }

    private fun startInternal() {
        val svc = service ?: return
        viewModelScope.launch {
            val s = g.settings.current()
            when (val r = svc.start(RecordingConfig(maxDurationMs = 30 * 60_000L, trimSilence = false, gain = s.micGain))) {
                is Outcome.Failure -> { _ui.update { it.copy(phase = NotesUi.Phase.Idle, error = r.error) }; unbind() ; return@launch }
                is Outcome.Success -> Unit
            }
            stateJob?.cancel()
            stateJob = launch { svc.state.collectLatest { st -> _ui.update { it.copy(recording = st) } } }
        }
    }

    fun pauseResume() { service?.let { if (it.state.value is RecordingState.Recording) it.pause() else it.resume() } }

    fun stopRecording() = viewModelScope.launch {
        val svc = service ?: return@launch
        when (val r = svc.stop()) {
            is Outcome.Failure -> _ui.update { it.copy(phase = NotesUi.Phase.Idle, error = r.error) }
            is Outcome.Success -> {
                val retention = g.settings.current().audioRetention
                _ui.update { it.copy(pendingAudio = r.value, askKeepAudio = retention == AudioRetention.ASK, phase = NotesUi.Phase.Transcribing) }
                if (retention != AudioRetention.ASK) finalizeNote(keepAudio = retention == AudioRetention.ALWAYS)
            }
        }
        unbind()
    }

    fun cancelRecording() = viewModelScope.launch { service?.cancel(); unbind(); _ui.update { it.copy(phase = NotesUi.Phase.Idle, recording = RecordingState.Idle) } }

    fun finalizeNote(keepAudio: Boolean) = viewModelScope.launch {
        val audio = _ui.value.pendingAudio ?: return@launch
        _ui.update { it.copy(askKeepAudio = false, phase = NotesUi.Phase.Transcribing) }
        val s = g.settings.current()
        val id = NotesRepository.newId()
        if (keepAudio) withContext(g.dispatchers.io) { WavCodec.write(g.notes.audioFile(id), audio.samples, audio.sampleRate) }
        val now = System.currentTimeMillis()
        when (val r = g.pipeline.process(audio, s, DictationMode.CLEAN)) {
            is Outcome.Failure -> {
                // Keep the audio so the user can retry transcription later.
                if (!keepAudio) withContext(g.dispatchers.io) { WavCodec.write(g.notes.audioFile(id), audio.samples, audio.sampleRate) }
                g.notes.save(VoiceNote(id, "", now, now, audio.durationMs, s.inputLanguage, null, "", null, true, null))
                _ui.update { it.copy(phase = NotesUi.Phase.Idle, pendingAudio = null, error = r.error) }
            }
            is Outcome.Success -> {
                val res = r.value
                val lang = res.transcript.language.takeIf { it != Language.AUTO } ?: s.inputLanguage
                val segments = res.transcript.segments.joinToString("\n") { "${it.startMs}\t${it.endMs}\t${it.text}" }
                g.notes.save(VoiceNote(id, res.displayText.take(40), now, now, audio.durationMs, lang, res.translationLanguage, res.displayText, res.translation, keepAudio, segments))
                _ui.update { it.copy(phase = NotesUi.Phase.Idle, pendingAudio = null) }
            }
        }
    }

    private fun unbind() {
        stateJob?.cancel()
        runCatching { getApplication<Application>().unbindService(connection) }
        service = null
    }

    override fun onCleared() { unbind(); super.onCleared() }
}

@Composable
fun NotesScreen(nav: NavHostController, vm: NotesViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val settings by SemaGraph.settings.settings.collectAsState(initial = null)
    val list by (if (ui.query.isBlank()) SemaGraph.notes.observeAll() else SemaGraph.notes.search(ui.query)).collectAsState(initial = emptyList())
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.startRecording() }
    val s = settings ?: return

    if (ui.askKeepAudio) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.notes_keep_audio)) },
        text = { Text(stringResource(R.string.notes_keep_audio_desc)) },
        confirmButton = { TextButton(onClick = { vm.finalizeNote(true) }) { Text(stringResource(R.string.notes_keep)) } },
        dismissButton = { TextButton(onClick = { vm.finalizeNote(false) }) { Text(stringResource(R.string.notes_discard_audio)) } },
    )

    Column(Modifier.fillMaxSize()) {
        SemaTopBar(stringResource(R.string.notes_title))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BigMicButton(
                recording = ui.phase == NotesUi.Phase.Recording, processing = ui.phase == NotesUi.Phase.Transcribing, enabled = true, large = s.largeMicTarget,
            ) {
                when (ui.phase) {
                    NotesUi.Phase.Recording -> vm.stopRecording()
                    NotesUi.Phase.Idle -> if (SemaGraph.newRecorder().hasPermission()) vm.startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)
                    else -> Unit
                }
            }
            VSpace(8)
            when (ui.phase) {
                NotesUi.Phase.Recording -> {
                    val rec = ui.recording
                    val elapsed = (rec as? RecordingState.Recording)?.elapsedMs ?: (rec as? RecordingState.Paused)?.elapsedMs ?: 0L
                    Text("${stringResource(R.string.dictation_recording)} ${Format.duration(elapsed)}", color = Brand.Record)
                    LinearProgressIndicator(progress = { (rec as? RecordingState.Recording)?.level ?: 0f }, modifier = Modifier.fillMaxWidth(0.6f).padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.pauseResume() }) { Text(stringResource(if (rec is RecordingState.Paused) R.string.action_resume else R.string.action_pause)) }
                        TextButton(onClick = { vm.cancelRecording() }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                NotesUi.Phase.Transcribing -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp)); Text(stringResource(R.string.dictation_processing)) }
                else -> Text(stringResource(R.string.notes_record), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ui.error?.let { VSpace(8); ErrorBanner(it) }
            VSpace(8)
            OutlinedTextField(value = ui.query, onValueChange = { vm.setQuery(it) }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.notes_search)) }, singleLine = true)
        }
        if (list.isEmpty()) Text(stringResource(R.string.notes_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { n ->
                SemaCard(onClick = { nav.navigate(Routes.note(n.id)) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(n.title.ifBlank { stringResource(R.string.notes_untitled) }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(Format.duration(n.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(n.transcript.ifBlank { stringResource(R.string.notes_no_audio) }.take(140), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    Text("${n.language.nativeName} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(n.createdAt)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun NoteDetailScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var note by remember { mutableStateOf<VoiceNote?>(null) }
    var title by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSegments by remember { mutableStateOf(false) }
    LaunchedEffect(id) { SemaGraph.notes.byId(id)?.let { note = it; title = it.title; transcript = it.transcript } }
    val n = note ?: return

    fun save(update: (VoiceNote) -> VoiceNote) = scope.launch {
        val updated = update(n).copy(updatedAt = System.currentTimeMillis())
        SemaGraph.notes.save(updated); note = updated
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(R.string.notes_delete_confirm)) },
        confirmButton = { TextButton(onClick = { scope.launch { SemaGraph.notes.delete(id); nav.popBackStack() } }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
    )

    Column(Modifier.fillMaxSize()) {
        SemaTopBar(n.title.ifBlank { stringResource(R.string.notes_untitled) }, onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it; save { v -> v.copy(title = it) } }, label = { Text(stringResource(R.string.action_rename)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            VSpace()
            OutlinedTextField(value = transcript, onValueChange = { transcript = it; save { v -> v.copy(transcript = it) } }, label = { Text(stringResource(R.string.dictation_result)) }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            n.translation?.let { VSpace(8); Text(stringResource(R.string.dictation_translation), style = MaterialTheme.typography.labelSmall); Text(it, color = Brand.Primary) }
            Text("${n.language.nativeName} · ${Format.duration(n.durationMs)} · " + stringResource(if (n.hasAudio) R.string.notes_export_audio else R.string.notes_no_audio), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VSpace()
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { Clipboard.copy(ctx, transcript) }) { Text(stringResource(R.string.action_copy)) }
                OutlinedButton(onClick = { Clipboard.share(ctx, transcript) }) { Text(stringResource(R.string.action_share)) }
                OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        val s = SemaGraph.settings.current()
                        when (val t = SemaGraph.pipeline.translateText(transcript, n.language, s.outputLanguage, s)) {
                            is Outcome.Failure -> error = t.error
                            is Outcome.Success -> save { v -> v.copy(translation = t.value, translationLanguage = s.outputLanguage) }
                        }
                        busy = false
                    }
                }, enabled = !busy) { Text(stringResource(R.string.action_translate)) }
                if (n.hasAudio) OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        val wav = withContext(SemaGraph.dispatchers.io) { WavCodec.read(SemaGraph.notes.audioFile(id)) }
                        when (val r = SemaGraph.pipeline.process(PcmAudio(wav.samples, wav.sampleRate), SemaGraph.settings.current(), DictationMode.CLEAN)) {
                            is Outcome.Failure -> error = r.error
                            is Outcome.Success -> { transcript = r.value.displayText; save { v -> v.copy(transcript = r.value.displayText, title = v.title.ifBlank { r.value.displayText.take(40) }) } }
                        }
                        busy = false
                    }
                }, enabled = !busy) { Text(stringResource(R.string.notes_transcribe)) }
                if (n.hasAudio) OutlinedButton(onClick = {
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", SemaGraph.notes.audioFile(id))
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, null))
                }) { Text(stringResource(R.string.notes_export_audio)) }
                if (n.hasAudio) TextButton(onClick = { scope.launch { SemaGraph.notes.deleteAudioOnly(id); note = SemaGraph.notes.byId(id) } }) { Text(stringResource(R.string.notes_delete_audio)) }
                if (!n.segmentsJson.isNullOrBlank()) TextButton(onClick = { showSegments = !showSegments }) { Text(stringResource(R.string.notes_segments)) }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { VSpace(8); ErrorBanner(it) }
            if (showSegments) n.segmentsJson?.lines()?.forEach { line ->
                val cols = line.split('\t')
                if (cols.size == 3) Text("${Format.duration(cols[0].toLongOrNull() ?: 0)} – ${Format.duration(cols[1].toLongOrNull() ?: 0)}  ${cols[2]}", style = MaterialTheme.typography.bodyMedium)
            }
            VSpace(40)
        }
    }
}
