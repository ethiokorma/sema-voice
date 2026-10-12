package com.semavoice.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.semavoice.core.audio.RecordingState
import com.semavoice.core.common.Language
import com.semavoice.core.settings.DictationMode
import com.semavoice.core.settings.KeyboardHeight
import com.semavoice.core.settings.ThemeMode

/** Brand palette (section 3). */
object SemaColors {
    val Primary = Color(0xFF4F46E5)
    val Secondary = Color(0xFF14B8A6)
    val LightBg = Color(0xFFF8FAFC)
    val DarkBg = Color(0xFF0B1220)
    val Text = Color(0xFF111827)
    val Border = Color(0xFFE5E7EB)
    val Record = Color(0xFFEF4444)
    val KeyLight = Color.White
    val KeyDark = Color(0xFF1E293B)
    val KeyAltLight = Color(0xFFE2E8F0)
    val KeyAltDark = Color(0xFF334155)
}

data class KbTheme(val dark: Boolean, val highContrast: Boolean) {
    val bg get() = if (dark) SemaColors.DarkBg else SemaColors.LightBg
    val key get() = if (dark) SemaColors.KeyDark else SemaColors.KeyLight
    val keyAlt get() = if (dark) SemaColors.KeyAltDark else SemaColors.KeyAltLight
    val text get() = if (dark) Color(0xFFF1F5F9) else SemaColors.Text
    val subtle get() = if (dark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val border get() = if (dark) Color(0xFF334155) else SemaColors.Border
    val card get() = if (dark) Color(0xFF111A2E) else Color.White
}

@Composable
fun SemaKeyboard(controller: KeyboardController, onSwitchKeyboard: () -> Unit) {
    val state by controller.state.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (state.settings.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemDark }
    val theme = KbTheme(dark, state.settings.highContrast)
    val scheme = if (dark) darkColorScheme(primary = SemaColors.Primary, secondary = SemaColors.Secondary, background = SemaColors.DarkBg)
    else lightColorScheme(primary = SemaColors.Primary, secondary = SemaColors.Secondary, background = SemaColors.LightBg)
    val keyHeight = when (state.settings.keyboardHeight) { KeyboardHeight.COMPACT -> 40.dp; KeyboardHeight.NORMAL -> 46.dp; KeyboardHeight.TALL -> 54.dp }
    val view = LocalView.current
    val haptic: () -> Unit = { if (state.settings.hapticFeedback) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    MaterialTheme(colorScheme = scheme) {
        Column(
            modifier = Modifier.fillMaxWidth().background(theme.bg).navigationBarsPadding().padding(bottom = 4.dp).animateContentSize(),
        ) {
            Toolbar(state, theme, controller, onSwitchKeyboard)
            CenterArea(state, theme, controller)
            KeyRows(state, theme, controller, keyHeight, haptic, onSwitchKeyboard)
        }
    }
}

@Composable
private fun Toolbar(state: KeyboardUiState, t: KbTheme, c: KeyboardController, onSwitchKeyboard: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sema", color = SemaColors.Primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
        LangChip(state.settings.inputLanguage, t, stringResource(R.string.kb_input_label)) { c.cycleInputLanguage() }
        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = t.subtle, modifier = Modifier.padding(horizontal = 4.dp).size(16.dp))
        LangChip(state.settings.outputLanguage, t, stringResource(R.string.kb_output_label)) { c.cycleOutputLanguage() }
        Spacer(Modifier.weight(1f))
        if (state.settings.inputLanguage.script == com.semavoice.core.common.Script.GEEZ) {
            Text(
                if (state.geezInput) "ፊደል" else "ABC", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (state.geezInput) SemaColors.Primary else t.subtle,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { c.toggleGeezInput() }.padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Ge'ez phonetic typing" },
            )
        }
        if (state.settings.showTranslateToggle) {
            ToolbarIcon(Icons.Default.Translate, stringResource(R.string.kb_translate_toggle), t, active = state.showTranslation) { c.toggleTranslation() }
        }
        ToolbarIcon(Icons.Default.OpenInFull, stringResource(R.string.kb_expand), t) { c.openApp("dictation") }
        ToolbarIcon(Icons.Default.Settings, stringResource(R.string.kb_settings), t) { c.openApp("settings") }
    }
}

@Composable
private fun LangChip(language: Language, t: KbTheme, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(t.keyAlt).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "$label ${language.englishName}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(language.nativeName, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToolbarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, t: KbTheme, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(if (active) SemaColors.Primary.copy(alpha = 0.15f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = if (active) SemaColors.Primary else t.subtle, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun CenterArea(state: KeyboardUiState, t: KbTheme, c: KeyboardController) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 220.dp).padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp)).background(t.card).border(1.dp, t.border, RoundedCornerShape(16.dp)).padding(12.dp),
    ) {
        when (state.phase) {
            KeyboardUiState.Phase.Idle -> IdleContent(state, t, c)
            KeyboardUiState.Phase.Recording -> RecordingContent(state, t, c)
            KeyboardUiState.Phase.Processing -> ProcessingContent(state, t, c)
            KeyboardUiState.Phase.Review -> ReviewContent(state, t, c)
            KeyboardUiState.Phase.Error -> ErrorContent(state, t, c)
        }
    }
}

@Composable
private fun IdleContent(state: KeyboardUiState, t: KbTheme, c: KeyboardController) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val hint = when {
            state.fieldSecure -> stringResource(R.string.err_secure_field)
            !state.fieldPresent -> stringResource(R.string.err_no_field)
            !state.modelReady -> stringResource(R.string.err_model_missing)
            else -> stringResource(R.string.kb_tap_to_speak)
        }
        Text(hint, color = t.subtle, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeChip(stringResource(R.string.kb_mode_exact), state.settings.dictationMode == DictationMode.EXACT && !state.showTranslation, t) { c.setMode(DictationMode.EXACT) }
            ModeChip(stringResource(R.string.kb_mode_clean), state.settings.dictationMode == DictationMode.CLEAN && !state.showTranslation, t) { c.setMode(DictationMode.CLEAN) }
            ModeChip(stringResource(R.string.kb_mode_translate), state.showTranslation, t) { c.setMode(DictationMode.TRANSLATE) }
        }
        if (!state.modelReady) {
            Spacer(Modifier.height(6.dp))
            ActionButton(stringResource(R.string.err_model_missing_action), t, primary = true) { c.openApp("models") }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, t: KbTheme, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        color = if (selected) Color.White else t.text,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) SemaColors.Primary else t.keyAlt).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun RecordingContent(state: KeyboardUiState, t: KbTheme, c: KeyboardController) {
    val rec = state.recording
    val level = (rec as? RecordingState.Recording)?.level ?: 0f
    val elapsed = when (rec) { is RecordingState.Recording -> rec.elapsedMs; is RecordingState.Paused -> rec.elapsedMs; else -> 0L }
    val paused = rec is RecordingState.Paused
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (paused) t.subtle else SemaColors.Record))
            Spacer(Modifier.width(8.dp))
            Text(
                if (paused) stringResource(R.string.kb_paused) else stringResource(R.string.kb_listening),
                color = t.text, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text(formatMs(elapsed), color = t.subtle, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        LevelBars(level, paused)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(R.string.kb_cancel), t, icon = Icons.Default.Close) { c.cancelRecording() }
            ActionButton(if (paused) "▶" else "⏸", t, icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, iconOnly = true) { c.pauseOrResume() }
            ActionButton(stringResource(R.string.kb_stop), t, primary = true, icon = Icons.Default.Stop) { c.stopAndProcess() }
        }
    }
}

@Composable
private fun LevelBars(level: Float, paused: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
        val bars = 24
        for (i in 0 until bars) {
            val centerWeight = 1f - kotlin.math.abs(i - bars / 2f) / (bars / 2f)
            val h = (4 + 24 * level * (0.4f + 0.6f * centerWeight)).dp
            Box(Modifier.width(4.dp).height(if (paused) 4.dp else h).clip(RoundedCornerShape(2.dp)).background(if (paused) Color.Gray else SemaColors.Primary))
        }
    }
}

@Composable
private fun ProcessingContent(state: KeyboardUiState, t: KbTheme, c: KeyboardController) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = SemaColors.Primary)
            Spacer(Modifier.width(10.dp))
            val label = state.loadingLabel?.let { stringResource(R.string.kb_loading_model, it) }
                ?: stringResource(R.string.kb_transcribing) + if (state.transcribeProgress in 1..99) " ${state.transcribeProgress}%" else ""
            Text(label, color = t.text)
        }
        Text(stringResource(R.string.kb_processing_local), color = SemaColors.Secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        ActionButton(stringResource(R.string.kb_cancel), t, icon = Icons.Default.Close) { c.cancelProcessing() }
    }
}

@Composable
private fun ReviewContent(state: KeyboardUiState, t: KbTheme, c: KeyboardController) {
    val result = state.result ?: return
    val translation: String? = result.translation
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (translation != null) Text(stringResource(R.string.kb_original), color = t.subtle, fontSize = 11.sp)
        BasicTextField(
            value = state.effectiveText,
            onValueChange = { c.setEditedText(it) },
            textStyle = TextStyle(color = t.text, fontSize = 16.sp),
            cursorBrush = SolidColor(SemaColors.Primary),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Transcript" },
        )
        if (translation != null) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.kb_translated), color = t.subtle, fontSize = 11.sp)
            Text(translation, color = SemaColors.Primary, fontSize = 16.sp)
        }
        result.translationError?.let { Text(ErrorMessages.present(LocalView.current.context, it).message, color = SemaColors.Record, fontSize = 12.sp) }
        if (result.appliedCorrections.isNotEmpty()) {
            Text(stringResource(R.string.kb_corrections_applied, result.appliedCorrections.size), color = SemaColors.Secondary, fontSize = 11.sp)
        }
        result.suggestedCorrections.take(2).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.kb_suggestion, s.to), color = t.subtle, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.kb_apply), color = SemaColors.Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { c.applySuggestion(s.from, s.to) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(stringResource(R.string.kb_discard), t, icon = Icons.Default.Close) { c.discard() }
            ActionButton(stringResource(R.string.kb_retry), t, icon = Icons.Default.Refresh) { c.retry() }
            Spacer(Modifier.weight(1f))
            if (translation != null) {
                ActionButton(stringResource(R.string.kb_insert_original), t) { c.insert(useTranslation = false) }
                ActionButton(stringResource(R.string.kb_insert_translation), t, primary = true, icon = Icons.Default.Check) { c.insert(useTranslation = true) }
            } else {
                ActionButton(stringResource(R.string.kb_insert), t, primary = true, icon = Icons.Default.Check) { c.insert() }
            }
        }
    }
}

@Composable
private fun ErrorContent(state: KeyboardUiState, t: KbTheme, c: KeyboardController) {
    val error = state.error ?: return
    val p = ErrorMessages.present(LocalView.current.context, error)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(p.message, color = t.text, textAlign = TextAlign.Center, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(R.string.kb_discard), t) { c.discard() }
            if (p.actionLabel != null) ActionButton(p.actionLabel, t, primary = true) {
                when (p.actionRoute) { "retry" -> c.retry(); null -> c.discard(); else -> c.openApp(p.actionRoute) }
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String, t: KbTheme, primary: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, iconOnly: Boolean = false, onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (primary) SemaColors.Primary else t.keyAlt).clickable(onClick = onClick)
            .padding(horizontal = if (iconOnly) 10.dp else 12.dp, vertical = 8.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (primary) Color.White else t.text, modifier = Modifier.size(16.dp))
        if (!iconOnly) {
            if (icon != null) Spacer(Modifier.width(4.dp))
            Text(label, color = if (primary) Color.White else t.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ---- key rows ---------------------------------------------------------------------------

private val qwerty = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val symbolsLatin = listOf("1234567890", "@#\$%&-+()", "*\"':;!?/")
private val symbolsGeez = listOf("1234567890", "።፣፤፥፧-+()", "*\"'፦;!?/")

@Composable
private fun KeyRows(state: KeyboardUiState, t: KbTheme, c: KeyboardController, keyHeight: androidx.compose.ui.unit.Dp, haptic: () -> Unit, onSwitchKeyboard: () -> Unit) {
    val geez = state.settings.inputLanguage.script == com.semavoice.core.common.Script.GEEZ
    val rows = if (state.symbols) (if (geez) symbolsGeez else symbolsLatin) else qwerty
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (index == 2 && !state.symbols) {
                    Key(Modifier.weight(1.5f), keyHeight, t, alt = true, content = { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.kb_shift), tint = if (state.shift) SemaColors.Primary else t.text) }) { haptic(); c.toggleShift() }
                }
                if (index == 1 && !state.symbols) Spacer(Modifier.weight(0.5f))
                row.forEach { ch ->
                    val label = if (state.shift && !state.symbols) ch.uppercase() else ch.toString()
                    Key(Modifier.weight(1f), keyHeight, t, content = { Text(label, color = t.text, fontSize = 19.sp) }) { haptic(); c.typeKey(ch.toString()) }
                }
                if (index == 1 && !state.symbols) Spacer(Modifier.weight(0.5f))
                if (index == 2) {
                    Key(Modifier.weight(1.5f), keyHeight, t, alt = true, content = { Icon(Icons.Default.Backspace, stringResource(R.string.kb_backspace), tint = t.text) }) { haptic(); c.backspace() }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(Modifier.weight(1.3f), keyHeight, t, alt = true, content = { Text(if (state.symbols) "ABC" else "?123", color = t.text, fontSize = 13.sp) }) { haptic(); c.toggleSymbols() }
            Key(Modifier.weight(1f), keyHeight, t, alt = true, content = { Icon(Icons.Default.Language, stringResource(R.string.kb_switch_keyboard), tint = t.text, modifier = Modifier.size(20.dp)) }) { haptic(); onSwitchKeyboard() }
            MicKey(state, keyHeight, c, haptic)
            Key(Modifier.weight(3f), keyHeight, t, content = { Text(stringResource(R.string.kb_space), color = t.subtle, fontSize = 12.sp) }) { haptic(); c.space() }
            Key(Modifier.weight(1f), keyHeight, t, content = { Text(if (geez) "።" else ".", color = t.text, fontSize = 19.sp) }) { haptic(); c.typeKey(if (geez) "።" else ".") }
            Key(Modifier.weight(1.3f), keyHeight, t, alt = true, content = { Icon(Icons.AutoMirrored.Filled.KeyboardReturn, stringResource(R.string.kb_enter), tint = t.text, modifier = Modifier.size(20.dp)) }) { haptic(); c.enter() }
        }
    }
}

@Composable
private fun MicKey(state: KeyboardUiState, keyHeight: androidx.compose.ui.unit.Dp, c: KeyboardController, haptic: () -> Unit) {
    val recording = state.phase == KeyboardUiState.Phase.Recording
    val processing = state.phase == KeyboardUiState.Phase.Processing
    val enabled = state.micEnabled
    val size = if (state.settings.largeMicTarget) keyHeight + 14.dp else keyHeight + 6.dp
    val desc = if (recording) stringResource(R.string.kb_mic_stop) else stringResource(R.string.kb_mic)
    Box(
        modifier = Modifier.size(size).clip(CircleShape)
            .background(if (!enabled) Color.Gray else if (recording) SemaColors.Record else SemaColors.Primary)
            .clickable(enabled = enabled && !processing) { haptic(); c.toggleMic() }
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        if (processing) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        else Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun Key(modifier: Modifier, height: androidx.compose.ui.unit.Dp, t: KbTheme, alt: Boolean = false, content: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        modifier = modifier.height(height).clip(RoundedCornerShape(8.dp)).background(if (alt) t.keyAlt else t.key).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun formatMs(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
