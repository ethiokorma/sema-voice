package com.semavoice.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.semavoice.app.R
import com.semavoice.app.Routes
import com.semavoice.app.ui.Brand
import com.semavoice.app.ui.Clipboard
import com.semavoice.app.ui.ErrorBanner
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.SemaTopBar
import com.semavoice.app.ui.VSpace
import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.database.HistoryItem
import com.semavoice.core.database.HistoryKind
import com.semavoice.core.database.HistoryRepository
import com.semavoice.core.runtime.SemaGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranslateUi(
    val input: String = "",
    val output: String = "",
    val from: Language = Language.AMHARIC,
    val to: Language = Language.ENGLISH,
    val busy: Boolean = false,
    val error: AppError? = null,
    val inferenceMs: Long = 0,
    val saved: Boolean = false,
)

class TranslateViewModel : ViewModel() {
    private val g = SemaGraph
    private val _ui = MutableStateFlow(TranslateUi())
    val ui: StateFlow<TranslateUi> = _ui.asStateFlow()

    init { viewModelScope.launch { val s = g.settings.current(); _ui.update { it.copy(from = s.inputLanguage.takeIf { l -> l != Language.AUTO } ?: Language.AMHARIC, to = s.outputLanguage) } } }

    fun setInput(t: String) { _ui.update { it.copy(input = t, saved = false) } }
    fun setFrom(l: Language) { _ui.update { it.copy(from = l, error = null) } }
    fun setTo(l: Language) { _ui.update { it.copy(to = l, error = null) } }
    fun swap() { _ui.update { it.copy(from = it.to, to = it.from, input = it.output.ifBlank { it.input }, output = "", error = null) } }

    fun translate() = viewModelScope.launch {
        val u = _ui.value
        if (u.input.isBlank()) return@launch
        _ui.update { it.copy(busy = true, error = null, saved = false) }
        val t0 = System.currentTimeMillis()
        when (val r = g.pipeline.translateText(u.input, u.from, u.to, g.settings.current())) {
            is Outcome.Failure -> _ui.update { it.copy(busy = false, error = r.error) }
            is Outcome.Success -> _ui.update { it.copy(busy = false, output = r.value, inferenceMs = System.currentTimeMillis() - t0) }
        }
    }

    fun save() = viewModelScope.launch {
        val u = _ui.value
        g.history.save(HistoryItem(HistoryRepository.newId(), System.currentTimeMillis(), HistoryKind.TRANSLATION, u.from, u.to, u.input, u.output, false, null))
        _ui.update { it.copy(saved = true) }
    }

    fun speak(text: String, language: Language) = viewModelScope.launch {
        g.tts.initialize().onSuccess { g.tts.speak(text, language).onFailure { e -> _ui.update { it.copy(error = e) } } }
    }
}

@Composable
fun TranslateScreen(nav: NavHostController, initialText: String? = null, onConsumedShared: () -> Unit = {}, vm: TranslateViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(initialText) { if (!initialText.isNullOrBlank()) { vm.setInput(initialText); onConsumedShared() } }

    Column(Modifier.fillMaxSize()) {
        SemaTopBar(stringResource(R.string.translate_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                LanguagePicker(ui.from, Language.outputCandidates) { vm.setFrom(it) }
                IconButton(onClick = { vm.swap() }) { Icon(Icons.Default.SwapHoriz, stringResource(R.string.action_swap)) }
                LanguagePicker(ui.to, Language.outputCandidates) { vm.setTo(it) }
            }
            SemaCard {
                Text(stringResource(R.string.translate_original), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = ui.input, onValueChange = { vm.setInput(it) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    placeholder = { Text(stringResource(R.string.translate_hint)) }, textStyle = MaterialTheme.typography.bodyLarge,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.translate() }, enabled = !ui.busy && ui.input.isNotBlank()) {
                        if (ui.busy) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Text(stringResource(R.string.translate_go))
                    }
                    OutlinedButton(onClick = { nav.navigate(Routes.DICTATION) }) { Text(stringResource(R.string.home_voice_to_text)) }
                    TextButton(onClick = { Clipboard.copy(ctx, ui.input) }, enabled = ui.input.isNotBlank()) { Text(stringResource(R.string.translate_copy_original)) }
                }
            }
            VSpace()
            if (ui.output.isNotBlank()) SemaCard {
                Text(stringResource(R.string.translate_translated) + " · " + ui.to.nativeName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ui.output, style = MaterialTheme.typography.bodyLarge, color = Brand.Primary)
                Text("${ui.inferenceMs} ms · " + stringResource(R.string.processed_locally), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { Clipboard.copy(ctx, ui.output) }) { Text(stringResource(R.string.translate_copy_translation)) }
                    OutlinedButton(onClick = { Clipboard.share(ctx, ui.output) }) { Text(stringResource(R.string.action_share)) }
                    TextButton(onClick = { vm.speak(ui.output, ui.to) }) { Text(stringResource(R.string.action_listen)) }
                }
                TextButton(onClick = { vm.save() }, enabled = !ui.saved) { Text(stringResource(if (ui.saved) R.string.saved else R.string.action_save_history)) }
            }
            ui.error?.let { e ->
                VSpace()
                val toModels = e is AppError.TranslationUnavailable || e is AppError.ModelNotInstalled || e is AppError.ModelLoadFailed
                ErrorBanner(e, if (toModels) stringResource(R.string.settings_models) else null) { nav.navigate(Routes.MODELS) }
            }
            VSpace(40)
        }
    }
}

@Composable
fun LanguagePicker(selected: Language, options: List<Language>, onSelect: (Language) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }) { Text(selected.nativeName) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { l -> DropdownMenuItem(text = { Text("${l.nativeName} · ${l.englishName}") }, onClick = { onSelect(l); open = false }) }
        }
    }
}
