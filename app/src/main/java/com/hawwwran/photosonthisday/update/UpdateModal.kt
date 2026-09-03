package com.hawwwran.photosonthisday.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hawwwran.photosonthisday.R
import com.hawwwran.photosonthisday.ui.formatBytes

@Composable
fun UpdateModal(
    state: UpdateUiState,
    open: Boolean,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetry: () -> Unit,
    /** Plan B when the system installer refuses the APK: the Play Protect settings page. */
    onOpenPlayProtect: () -> Unit,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleFor(state)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { BodyContent(state, onOpenPlayProtect) }
        },
        confirmButton = { ConfirmButton(state, onInstall, onCancelDownload, onRetry, onDismiss) },
        dismissButton = { DismissButton(state, onSkip) },
    )
}

@Composable
private fun titleFor(state: UpdateUiState): String = stringResource(
    when (state) {
        UpdateUiState.Idle -> R.string.update_title_check
        is UpdateUiState.Checking -> R.string.update_title_checking
        is UpdateUiState.NoUpdate -> R.string.update_title_uptodate
        UpdateUiState.CheckFailed -> R.string.update_title_check_failed
        is UpdateUiState.Available -> R.string.update_title_available
        is UpdateUiState.Downloading -> R.string.update_title_downloading
        is UpdateUiState.Launching -> R.string.update_title_launching
        is UpdateUiState.NeedsPermission -> R.string.update_title_needs_permission
        is UpdateUiState.Error -> R.string.update_title_error
    },
)

@Composable
private fun BodyContent(state: UpdateUiState, onOpenPlayProtect: () -> Unit) {
    when (state) {
        UpdateUiState.Idle -> Text(stringResource(R.string.update_body_idle))
        is UpdateUiState.Checking -> CircularProgressIndicator()
        is UpdateUiState.NoUpdate -> {
            Text(stringResource(R.string.update_body_uptodate, state.currentVersion))
            if (state.stale) {
                Text(
                    stringResource(R.string.update_body_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        UpdateUiState.CheckFailed -> Text(stringResource(R.string.update_body_check_failed))
        is UpdateUiState.Available -> {
            Text(
                stringResource(R.string.update_body_available, state.info.currentVersion, state.info.latestVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.info.stale) {
                Text(
                    stringResource(R.string.update_body_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.info.releaseNotes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.info.releaseNotes.trim().take(800),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The app never learns why the system installer failed (ACTION_VIEW has no result), so
            // the likeliest cause is named here, where the user lands again after a failure: Play
            // Protect scans sideloaded APKs and rejected v1.0.1 on the owner's phone (2026-09-03).
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.update_play_protect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenPlayProtect, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.update_action_play_protect))
            }
        }
        is UpdateUiState.Downloading -> {
            val pct = (state.progress * 100).toInt().coerceIn(0, 100)
            Text("$pct%  (${formatBytes(state.bytesRead)} / ${formatBytes(state.total)})")
            Spacer(Modifier.height(8.dp))
            if (state.total > 0) {
                LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is UpdateUiState.Launching -> {
            CircularProgressIndicator()
            Text(stringResource(R.string.update_body_launching))
        }
        is UpdateUiState.NeedsPermission -> Text(stringResource(R.string.update_body_needs_permission))
        is UpdateUiState.Error -> Text(stringResource(errorText(state.reason)), color = MaterialTheme.colorScheme.error)
    }
}

private fun errorText(reason: UpdateFailure): Int = when (reason) {
    UpdateFailure.DOWNLOAD_HTTP -> R.string.update_error_download_http
    UpdateFailure.DOWNLOAD_INCOMPLETE -> R.string.update_error_download_incomplete
    UpdateFailure.DOWNLOAD_IO -> R.string.update_error_download_io
    UpdateFailure.FILE_GONE -> R.string.update_error_file_gone
    UpdateFailure.INSTALLER -> R.string.update_error_installer
}

@Composable
private fun ConfirmButton(
    state: UpdateUiState,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> TextButton(onClick = onInstall) { Text(stringResource(R.string.update_action_install)) }
        is UpdateUiState.NeedsPermission -> TextButton(onClick = onInstall) { Text(stringResource(R.string.update_action_open_settings)) }
        is UpdateUiState.Downloading -> TextButton(onClick = onCancelDownload) { Text(stringResource(R.string.update_action_cancel)) }
        is UpdateUiState.Error, UpdateUiState.CheckFailed -> TextButton(onClick = onRetry) { Text(stringResource(R.string.update_action_retry)) }
        UpdateUiState.Idle, is UpdateUiState.NoUpdate -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_action_close)) }
        is UpdateUiState.Checking, is UpdateUiState.Launching -> {} // transient; no action
    }
}

@Composable
private fun DismissButton(state: UpdateUiState, onSkip: () -> Unit) {
    if (state is UpdateUiState.Available) {
        if (state.dismissed) {
            TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.update_action_skipped)) }
        } else {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.update_action_skip)) }
        }
    }
}
