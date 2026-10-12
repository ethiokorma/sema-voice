package com.semavoice.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.semavoice.app.R
import com.semavoice.app.Routes
import com.semavoice.app.ui.BigMicButton
import com.semavoice.app.ui.Brand
import com.semavoice.app.ui.KeyboardStatus
import com.semavoice.app.ui.SectionTitle
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.StatusPill
import com.semavoice.app.ui.VSpace
import com.semavoice.core.models.ModelKind
import com.semavoice.core.runtime.SemaGraph
import java.util.Calendar

@Composable
fun HomeScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val settings by SemaGraph.settings.settings.collectAsState(initial = null)
    val models by SemaGraph.modelManager.models.collectAsState()
    val recent by SemaGraph.notes.observeRecent(3).collectAsState(initial = emptyList())
    var kbEnabled by remember { mutableStateOf(KeyboardStatus.isEnabled(ctx)) }
    var kbSelected by remember { mutableStateOf(KeyboardStatus.isSelected(ctx)) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { kbEnabled = KeyboardStatus.isEnabled(ctx); kbSelected = KeyboardStatus.isSelected(ctx); SemaGraph.modelManager.refresh() } }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val s = settings ?: return
    val speechReady = models.any { it.info.kind == ModelKind.SPEECH && it.isUsable }
    val amharicReady = models.any { it.info.kind == ModelKind.SPEECH && it.isUsable && com.semavoice.core.common.Language.AMHARIC in it.info.languages }
    val translationReady = models.any { it.info.kind == ModelKind.TRANSLATION && it.isUsable }
    val offlineReady = speechReady && (s.inputLanguage != com.semavoice.core.common.Language.AMHARIC || amharicReady)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("SEMA VOICE", style = MaterialTheme.typography.labelLarge, color = Brand.Primary, fontWeight = FontWeight.Bold)
            Text(greeting(), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SemaCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text(stringResource(R.string.home_input), style = MaterialTheme.typography.labelSmall); Text(s.inputLanguage.nativeName, style = MaterialTheme.typography.titleMedium) }
                    Column(horizontalAlignment = Alignment.End) { Text(stringResource(R.string.home_output), style = MaterialTheme.typography.labelSmall); Text(s.outputLanguage.nativeName, style = MaterialTheme.typography.titleMedium) }
                }
                VSpace(16)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BigMicButton(recording = false, processing = false, enabled = true, large = s.largeMicTarget) { nav.navigate(Routes.DICTATION) }
                    VSpace(8)
                    Text(stringResource(R.string.home_speak), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VSpace(12)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(stringResource(if (offlineReady) R.string.home_status_ready else R.string.home_status_missing), offlineReady)
                    if (!offlineReady) TextButton(onClick = { nav.navigate(Routes.MODELS) }) { Text(stringResource(R.string.home_download)) }
                }
                if (!offlineReady) Text(stringResource(R.string.home_status_detail_missing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!translationReady) Text(stringResource(R.string.translate_no_model, s.inputLanguage.englishName, s.outputLanguage.englishName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SemaCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        stringResource(when { kbSelected -> R.string.home_keyboard_selected; kbEnabled -> R.string.home_keyboard_enabled; else -> R.string.home_keyboard_disabled }),
                        kbEnabled,
                    )
                    when {
                        !kbEnabled -> TextButton(onClick = { KeyboardStatus.openEnableSettings(ctx) }) { Text(stringResource(R.string.home_keyboard_enable)) }
                        !kbSelected -> TextButton(onClick = { KeyboardStatus.showPicker(ctx) }) { Text(stringResource(R.string.home_keyboard_select)) }
                    }
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.home_quick_actions))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Modifier.weight(1f), Icons.Default.Mic, stringResource(R.string.home_voice_to_text)) { nav.navigate(Routes.DICTATION) }
                QuickAction(Modifier.weight(1f), Icons.Default.Translate, stringResource(R.string.home_translate)) { nav.navigate(Routes.TRANSLATE) }
            }
            VSpace(10)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Modifier.weight(1f), Icons.Default.StickyNote2, stringResource(R.string.home_notes)) { nav.navigate(Routes.NOTES) }
                QuickAction(Modifier.weight(1f), Icons.Default.Keyboard, stringResource(R.string.home_keyboard)) { nav.navigate(Routes.SETTINGS) }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_recent)) }
            items(recent, key = { it.id }) { n ->
                SemaCard(onClick = { nav.navigate(Routes.note(n.id)) }) {
                    Text(n.title.ifBlank { stringResource(R.string.notes_untitled) }, style = MaterialTheme.typography.titleMedium)
                    Text(n.transcript.take(120), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun QuickAction(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    SemaCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Brand.Primary)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return stringResource(when { h < 12 -> R.string.greeting_morning; h < 18 -> R.string.greeting_afternoon; else -> R.string.greeting_evening })
}
