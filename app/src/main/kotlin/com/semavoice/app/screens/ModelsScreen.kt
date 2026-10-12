package com.semavoice.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.semavoice.app.R
import com.semavoice.app.ui.Brand
import com.semavoice.app.ui.ErrorBanner
import com.semavoice.app.ui.Format
import com.semavoice.app.ui.SectionTitle
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.SemaTopBar
import com.semavoice.app.ui.StatusPill
import com.semavoice.app.ui.VSpace
import com.semavoice.core.common.Language
import com.semavoice.core.models.CompatibilityRules
import com.semavoice.core.models.InstallState
import com.semavoice.core.models.ModelKind
import com.semavoice.core.models.ModelState
import com.semavoice.core.models.PerformanceTier
import com.semavoice.core.runtime.SemaGraph
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val models by SemaGraph.modelManager.models.collectAsState()
    val settings by SemaGraph.settings.settings.collectAsState(initial = null)
    var cellularPrompt by remember { mutableStateOf<ModelState?>(null) }
    var deletePrompt by remember { mutableStateOf<ModelState?>(null) }
    val s = settings ?: return
    val device = SemaGraph.modelManager.device
    androidx.compose.runtime.LaunchedEffect(s.modelSourceUrl) {
        if (s.modelSourceUrl.startsWith("https://")) SemaGraph.modelManager.refreshRemoteCatalog(s.modelSourceUrl)
    }

    cellularPrompt?.let { m ->
        AlertDialog(
            onDismissRequest = { cellularPrompt = null },
            text = { Text(stringResource(R.string.models_cellular_warning, Format.bytes(m.info.totalBytes))) },
            confirmButton = { TextButton(onClick = { SemaGraph.modelManager.download(m.info.id, wifiOnly = false); cellularPrompt = null }) { Text(stringResource(R.string.models_download)) } },
            dismissButton = { TextButton(onClick = { cellularPrompt = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deletePrompt?.let { m ->
        AlertDialog(
            onDismissRequest = { deletePrompt = null },
            text = { Text(stringResource(R.string.models_delete_confirm, m.info.displayName)) },
            confirmButton = { TextButton(onClick = { scope.launch { SemaGraph.speech.release(); SemaGraph.translator.release(); SemaGraph.modelManager.delete(m.info.id) }; deletePrompt = null }) { Text(stringResource(R.string.models_delete)) } },
            dismissButton = { TextButton(onClick = { deletePrompt = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    fun startDownload(m: ModelState) {
        if (s.wifiOnlyDownloads && !SemaGraph.modelManager.isWifiConnected() && SemaGraph.modelManager.isOnline()) cellularPrompt = m
        else SemaGraph.modelManager.download(m.info.id, s.wifiOnlyDownloads)
    }

    Column(Modifier.fillMaxSize()) {
        SemaTopBar(stringResource(R.string.models_title), onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    stringResource(R.string.models_device_report, device.abi, device.totalRamMb, device.cores, device.tier.name) + " · " + stringResource(R.string.settings_storage_used, Format.bytes(SemaGraph.modelManager.storageUsedBytes())),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (models.none { it.info.kind == ModelKind.SPEECH && Language.AMHARIC in it.info.languages }) {
                    VSpace(8); Text(stringResource(R.string.models_amharic_note), color = Brand.Record, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { SectionTitle(stringResource(R.string.models_speech)) }
            items(models.filter { it.info.kind == ModelKind.SPEECH }, key = { it.info.id }) { m -> ModelCard(m, s.preferredSpeechModelId == m.info.id, ::startDownload, { deletePrompt = it }) { scope.launch { SemaGraph.settings.setPreferredSpeechModel(it.info.id) } } }
            item { SectionTitle(stringResource(R.string.models_translation)) }
            items(models.filter { it.info.kind == ModelKind.TRANSLATION }, key = { it.info.id }) { m -> ModelCard(m, s.preferredTranslationModelId == m.info.id, ::startDownload, { deletePrompt = it }) { scope.launch { SemaGraph.settings.setPreferredTranslationModel(it.info.id) } } }
        }
    }
}

@Composable
private fun ModelCard(m: ModelState, isDefault: Boolean, onDownload: (ModelState) -> Unit, onDelete: (ModelState) -> Unit, onSetDefault: (ModelState) -> Unit) {
    val scope = rememberCoroutineScope()
    SemaCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(m.info.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (isDefault && m.isInstalled) Text(stringResource(R.string.models_default), color = Brand.Secondary, style = MaterialTheme.typography.labelSmall)
        }
        Text(m.info.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.models_languages, m.info.languages.joinToString { it.nativeName }), style = MaterialTheme.typography.labelSmall)
        Text(
            Format.bytes(m.info.totalBytes) + " · " + stringResource(R.string.models_ram, m.info.requiredRamMb) + " · " + stringResource(
                when (m.info.minTier) { PerformanceTier.LOW -> R.string.models_tier_low; PerformanceTier.MID -> R.string.models_tier_mid; PerformanceTier.HIGH -> R.string.models_tier_high },
            ) + " · v" + m.info.version,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(stringResource(R.string.models_license, m.info.license), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (m.info.experimental) Text(stringResource(R.string.models_experimental), color = Brand.Record, style = MaterialTheme.typography.labelSmall)
        when (val v = m.compatibility) {
            is CompatibilityRules.Verdict.TooLittleRam -> Text(stringResource(R.string.models_incompatible_ram), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            is CompatibilityRules.Verdict.MissingCpuFeatures, is CompatibilityRules.Verdict.UnsupportedAbi -> Text(stringResource(R.string.models_incompatible_cpu), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            is CompatibilityRules.Verdict.SlowTier -> Text(stringResource(R.string.models_slow), color = Brand.Record, style = MaterialTheme.typography.labelSmall)
            else -> Unit
        }
        VSpace(8)
        when (val st = m.install) {
            is InstallState.Installed -> {
                StatusPill(stringResource(R.string.models_installed, Format.bytes(st.sizeBytes)), true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!isDefault) TextButton(onClick = { onSetDefault(m) }) { Text(stringResource(R.string.models_set_default)) }
                    TextButton(onClick = { scope.launch { SemaGraph.modelManager.verify(m.info.id) } }) { Text(stringResource(R.string.models_verify)) }
                    TextButton(onClick = { onDelete(m) }) { Text(stringResource(R.string.models_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
            is InstallState.Downloading -> {
                Text(stringResource(R.string.models_downloading, (st.progress * 100).toInt()) + " · ${Format.bytes(st.bytesDone)} / ${Format.bytes(st.bytesTotal)} · ${st.currentFile}", style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                TextButton(onClick = { SemaGraph.modelManager.cancelDownload(m.info.id) }) { Text(stringResource(R.string.models_cancel)) }
            }
            is InstallState.Verifying -> { Text(stringResource(R.string.models_verifying), style = MaterialTheme.typography.labelSmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            is InstallState.Failed -> {
                ErrorBanner(st.error, stringResource(R.string.action_retry)) { onDownload(m) }
            }
            InstallState.NotInstalled -> {
                val canRun = CompatibilityRules.canRun(m.info, SemaGraph.modelManager.device)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { onDownload(m) }, enabled = canRun) { Text(stringResource(R.string.models_download) + " · " + Format.bytes(m.info.totalBytes)) }
                }
            }
        }
    }
}
