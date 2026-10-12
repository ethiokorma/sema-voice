package com.semavoice.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.semavoice.app.R
import com.semavoice.core.common.AppError

@Composable
fun SemaCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Card(
        modifier = m.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemaTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        actions = { actions() },
    )
}

/** The big microphone (section 4 / 6). Minimum 72dp target; 88dp when the accessibility setting is on. */
@Composable
fun BigMicButton(recording: Boolean, processing: Boolean, enabled: Boolean, large: Boolean, onClick: () -> Unit) {
    val size = if (large) 104.dp else 84.dp
    val desc = if (recording) stringResource(R.string.action_stop) else stringResource(R.string.home_mic)
    Box(
        modifier = Modifier.size(size).clip(CircleShape)
            .background(if (!enabled) MaterialTheme.colorScheme.outline else if (recording) Brand.Record else MaterialTheme.colorScheme.primary)
            .clickable(enabled = enabled && !processing, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        if (processing) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
        else Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (large) 48.dp else 38.dp))
    }
}

@Composable
fun ErrorBanner(error: AppError, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(AppErrors.message(ctx, error), color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun StatusPill(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Brand.Secondary else Brand.Record))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun VSpace(dp: Int = 12) = Spacer(Modifier.height(dp.dp))

object AppErrors {
    fun message(ctx: Context, e: AppError): String = when (e) {
        is AppError.MicrophonePermissionDenied -> ctx.getString(R.string.err_mic_permission)
        is AppError.NoMicrophone -> ctx.getString(R.string.err_no_mic)
        is AppError.MicrophoneBusy -> ctx.getString(R.string.err_mic_busy)
        is AppError.EmptyRecording -> ctx.getString(R.string.err_empty)
        is AppError.ModelNotInstalled -> ctx.getString(R.string.err_model_missing)
        is AppError.ModelCorrupt, is AppError.ModelLoadFailed -> ctx.getString(R.string.err_model_corrupt)
        is AppError.UnsupportedLanguage -> ctx.getString(R.string.err_unsupported_language, e.language.englishName)
        is AppError.TranslationUnavailable -> ctx.getString(R.string.err_translation_unavailable, e.from.englishName, e.to.englishName)
        is AppError.InsufficientStorage -> ctx.getString(R.string.err_storage, Format.bytes(e.neededBytes), Format.bytes(e.availableBytes))
        is AppError.OutOfMemoryRisk -> ctx.getString(R.string.err_memory)
        is AppError.NetworkUnavailable -> ctx.getString(R.string.err_network)
        is AppError.CellularDownloadBlocked -> ctx.getString(R.string.err_cellular)
        is AppError.ChecksumMismatch -> ctx.getString(R.string.err_checksum)
        is AppError.DownloadInterrupted -> ctx.getString(R.string.err_download)
        is AppError.UnsupportedDevice -> ctx.getString(R.string.err_device)
        is AppError.RecordingInterrupted, is AppError.RecordingFailed -> ctx.getString(R.string.err_interrupted)
        is AppError.TtsVoiceUnavailable -> ctx.getString(R.string.err_tts, e.language.englishName)
        is AppError.Cancelled -> ctx.getString(R.string.err_cancelled)
        else -> ctx.getString(R.string.err_generic)
    }
}

object Format {
    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> "%.0f MB".format(b / (1024.0 * 1024))
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    fun duration(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}

object Clipboard {
    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Sema Voice", text))
    }

    fun share(ctx: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Keyboard enablement status, read from the system InputMethodManager. */
object KeyboardStatus {
    private const val SERVICE = "com.semavoice.keyboard.SemaInputMethodService"

    fun isEnabled(ctx: Context): Boolean {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == ctx.packageName && it.serviceName == SERVICE }
    }

    fun isSelected(ctx: Context): Boolean {
        val current = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return current.startsWith(ctx.packageName + "/")
    }

    fun openEnableSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun showPicker(ctx: Context) {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }
}
