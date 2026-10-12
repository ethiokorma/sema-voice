package com.semavoice.app.screens

import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.SemaTopBar
import com.semavoice.core.common.Language
import com.semavoice.core.database.DictionaryCategory
import com.semavoice.core.database.DictionaryEntry
import com.semavoice.core.database.DictionaryRepository
import com.semavoice.core.runtime.SemaGraph
import kotlinx.coroutines.launch

@Composable
fun DictionaryScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by SemaGraph.dictionary.observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<DictionaryEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var importedCount by remember { mutableStateOf<Int?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@launch
            importedCount = SemaGraph.dictionary.importTsv(text)
        }
    }

    if (showEditor) EntryEditor(editing, onDismiss = { showEditor = false }) { e -> scope.launch { SemaGraph.dictionary.save(e) }; showEditor = false }

    Scaffold(
        floatingActionButton = { FloatingActionButton(onClick = { editing = null; showEditor = true }) { Icon(Icons.Default.Add, stringResource(R.string.dictionary_add)) } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            SemaTopBar(stringResource(R.string.dictionary_title), onBack = { nav.popBackStack() }) {
                TextButton(onClick = { importLauncher.launch(arrayOf("text/*")) }) { Text(stringResource(R.string.dictionary_import)) }
                TextButton(onClick = {
                    scope.launch {
                        val tsv = SemaGraph.dictionary.exportTsv()
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/tab-separated-values"; putExtra(Intent.EXTRA_TEXT, tsv) }, null))
                    }
                }) { Text(stringResource(R.string.dictionary_export)) }
            }
            importedCount?.let { Text(stringResource(R.string.dictionary_imported, it), modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary) }
            if (entries.isEmpty()) Text(stringResource(R.string.dictionary_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries, key = { it.id }) { e ->
                    SemaCard(onClick = { editing = e; showEditor = true }) {
                        Text(e.term, style = MaterialTheme.typography.titleMedium)
                        if (e.variants.isNotEmpty()) Text(e.variants.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${e.language.nativeName} · ${e.category.name.lowercase()} · ×${e.useCount}", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { scope.launch { SemaGraph.dictionary.delete(e) } }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryEditor(existing: DictionaryEntry?, onDismiss: () -> Unit, onSave: (DictionaryEntry) -> Unit) {
    var term by remember { mutableStateOf(existing?.term ?: "") }
    var variants by remember { mutableStateOf(existing?.variants?.joinToString(", ") ?: "") }
    var language by remember { mutableStateOf(existing?.language ?: Language.AMHARIC) }
    var category by remember { mutableStateOf(existing?.category ?: DictionaryCategory.NAME) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dictionary_add)) },
        text = {
            Column {
                OutlinedTextField(value = term, onValueChange = { term = it }, label = { Text(stringResource(R.string.dictionary_term)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = variants, onValueChange = { variants = it }, label = { Text(stringResource(R.string.dictionary_variants)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.dictionary_language), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                ChipRow(listOf(Language.AUTO, Language.AMHARIC, Language.ENGLISH, Language.SOMALI, Language.ARABIC).map { it.code to it.nativeName }, language.code) { language = Language.fromCode(it) }
                Text(stringResource(R.string.dictionary_category), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                ChipRow(
                    listOf(
                        DictionaryCategory.NAME.name to stringResource(R.string.category_name), DictionaryCategory.PLACE.name to stringResource(R.string.category_place),
                        DictionaryCategory.BUSINESS.name to stringResource(R.string.category_business), DictionaryCategory.TECH.name to stringResource(R.string.category_tech),
                        DictionaryCategory.OTHER.name to stringResource(R.string.category_other),
                    ),
                    category.name,
                ) { category = DictionaryCategory.valueOf(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = term.isNotBlank(), onClick = {
                onSave(
                    DictionaryEntry(
                        existing?.id ?: DictionaryRepository.newId(), term.trim(),
                        variants.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }, language, category,
                        existing?.createdAt ?: System.currentTimeMillis(), existing?.useCount ?: 0,
                    ),
                )
            }) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
