package com.semavoice.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.semavoice.app.R
import com.semavoice.app.ui.Brand
import com.semavoice.app.ui.Clipboard
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.SemaTopBar
import com.semavoice.core.common.Language
import com.semavoice.core.database.HistoryKind
import com.semavoice.core.runtime.SemaGraph
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<HistoryKind?>(null) }
    var language by remember { mutableStateOf<Language?>(null) }
    var favorites by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    val items by SemaGraph.history.query(query, kind, language, favorites).collectAsState(initial = emptyList())

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        text = { Text(stringResource(R.string.history_clear_confirm)) },
        confirmButton = { TextButton(onClick = { scope.launch { SemaGraph.history.deleteAll() }; confirmClear = false }) { Text(stringResource(R.string.history_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    editing?.let { id ->
        AlertDialog(
            onDismissRequest = { editing = null },
            text = { OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
            confirmButton = { TextButton(onClick = { scope.launch { SemaGraph.history.byId(id)?.let { SemaGraph.history.save(it.copy(original = editText)) } }; editing = null }) { Text(stringResource(R.string.action_done)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        SemaTopBar(stringResource(R.string.history_title)) {
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, stringResource(R.string.history_clear)) }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.history_search)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                FilterChip(selected = kind == null && !favorites, onClick = { kind = null; favorites = false }, label = { Text(stringResource(R.string.history_all)) })
                FilterChip(selected = kind == HistoryKind.TRANSCRIPTION, onClick = { kind = HistoryKind.TRANSCRIPTION }, label = { Text(stringResource(R.string.history_transcriptions)) })
                FilterChip(selected = kind == HistoryKind.TRANSLATION, onClick = { kind = HistoryKind.TRANSLATION }, label = { Text(stringResource(R.string.history_translations)) })
                FilterChip(selected = favorites, onClick = { favorites = !favorites }, label = { Text(stringResource(R.string.history_favorites)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = language == null, onClick = { language = null }, label = { Text(stringResource(R.string.history_all)) })
                listOf(Language.AMHARIC, Language.ENGLISH, Language.SOMALI, Language.ARABIC).forEach { l ->
                    FilterChip(selected = language == l, onClick = { language = if (language == l) null else l }, label = { Text(l.nativeName) })
                }
            }
        }
        if (items.isEmpty()) Text(stringResource(R.string.history_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { h ->
                SemaCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${h.sourceLanguage.nativeName}${h.targetLanguage?.let { " → " + it.nativeName } ?: ""} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(h.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { scope.launch { SemaGraph.history.save(h.copy(favorite = !h.favorite)) } }, modifier = Modifier.padding(0.dp)) {
                            Icon(if (h.favorite) Icons.Default.Star else Icons.Default.StarBorder, stringResource(R.string.history_favorites), tint = if (h.favorite) Brand.Secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(h.original, style = MaterialTheme.typography.bodyLarge)
                    h.result?.let { Text(it, color = Brand.Primary) }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { Clipboard.copy(ctx, h.result ?: h.original) }) { Text(stringResource(R.string.action_copy)) }
                        TextButton(onClick = { Clipboard.share(ctx, h.result ?: h.original) }) { Text(stringResource(R.string.action_share)) }
                        TextButton(onClick = { editText = h.original; editing = h.id }) { Text(stringResource(R.string.action_edit)) }
                        TextButton(onClick = { scope.launch { SemaGraph.history.delete(h.id) } }) { Text(stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
    }
}
